// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.fe10.codeInsight.compiler

import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.*
import org.jetbrains.kotlin.codegen.DefaultCodegenFactory
import org.jetbrains.kotlin.codegen.KotlinCodegenFacade
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.diagnostics.*
import org.jetbrains.kotlin.idea.base.codeInsight.compiler.CompilationOptions
import org.jetbrains.kotlin.idea.base.codeInsight.compiler.CompilationResult
import org.jetbrains.kotlin.idea.base.codeInsight.compiler.KotlinCompilerIde
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.core.util.InlineFunctionAnalyzer
import org.jetbrains.kotlin.load.kotlin.toSourceElement
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.source.PsiSourceFile
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource

internal class K1KotlinCompilerIde : KotlinCompilerIde {
    override fun compile(options: CompilationOptions): Result<CompilationResult> = runCatching {
        val file = options.file

        val platform = file.platform
        if (!platform.isCommon() && !platform.isJvm()) {
            throw IllegalArgumentException("Only JVM and Common platforms are supported")
        }

        val resolutionFacade = file.getResolutionFacade()

        val configuration = options.configuration
            .copy()
            .apply {
                put(JVMConfigurationKeys.DO_NOT_CLEAR_BINDING_CONTEXT, true)
            }

        val useIrBackend = configuration.getBoolean(JVMConfigurationKeys.IR)
        val disableInline = configuration.getBoolean(CommonConfigurationKeys.DISABLE_INLINE)

        // The binding context needs to be built from all files with reachable inline functions, as such files may contain classes whose
        // descriptors must be available in the binding context for the IR backend. Note that the full bytecode is only generated for
        // `file` because of filtering in `generateClassFilter`, while only select declarations from other files are generated if needed
        // by the backend.
        val inlineAnalyzer = InlineFunctionAnalyzer(resolutionFacade, analyzeOnlyReifiedInlineFunctions = disableInline)
        inlineAnalyzer.analyze(file)

        val filesToCompile = inlineAnalyzer.allFiles()
        val bindingContext = resolutionFacade.analyzeWithAllCompilerChecks(filesToCompile).bindingContext

        // The IR backend will try to regenerate object literals defined in inline functions from generated class files during inlining.
        // Hence, we need to be aware of which object declarations are defined in the relevant inline functions.
        val inlineObjectDeclarations = when {
            useIrBackend -> inlineAnalyzer.inlineObjectDeclarations()
            else -> setOf()
        }

        val inlineObjectDeclarationFiles = inlineObjectDeclarations.mapTo(mutableSetOf()) { it.containingKtFile }

        class GenerateClassFilter : GenerationState.GenerateClassFilter() {
            override fun shouldGeneratePackagePart(ktFile: KtFile): Boolean {
                return file === ktFile || inlineObjectDeclarationFiles.contains(ktFile)
            }

            override fun shouldAnnotateClass(processingClassOrObject: KtClassOrObject): Boolean {
                return true
            }

            override fun shouldGenerateClass(processingClassOrObject: KtClassOrObject): Boolean {
                return processingClassOrObject.containingKtFile === file ||
                        processingClassOrObject is KtObjectDeclaration && inlineObjectDeclarations.contains(processingClassOrObject)
            }

            override fun shouldGenerateScript(script: KtScript): Boolean {
                return script.containingKtFile === file
            }

            override fun shouldGenerateCodeFragment(script: KtCodeFragment) = false
        }

        val generateClassFilter = GenerateClassFilter()

        val codegenFactory = when {
            useIrBackend -> createJvmIrCodegenFactory(options)
            else -> DefaultCodegenFactory
        }

        val state = GenerationState.Builder(
            file.project,
            options.classBuilderFactory,
            resolutionFacade.moduleDescriptor,
            bindingContext,
            filesToCompile,
            configuration,
        ).generateDeclaredClassFilter(generateClassFilter)
            .codegenFactory(codegenFactory)
            .build()

        try {
            KotlinCodegenFacade.compileCorrectFiles(state)

            val outputFiles = state.factory.asList()
            val diagnostics = state.collectedExtraJvmDiagnostics
                .all()
                .filter { it.severity == Severity.ERROR }
                .map { it as KtDiagnostic }

            return@runCatching CompilationResult(outputFiles, diagnostics)
        } finally {
            state.destroy()
        }
    }

    private fun createJvmIrCodegenFactory(options: CompilationOptions): JvmIrCodegenFactory {
        val stubUnboundIrSymbols = options.stubUnboundIrSymbols
        val compilerConfiguration = options.configuration

        val jvmGeneratorExtensions = if (stubUnboundIrSymbols) {
            object : JvmGeneratorExtensionsImpl(compilerConfiguration) {
                override fun getContainerSource(descriptor: DeclarationDescriptor): DeserializedContainerSource? {
                    // Stubbed top-level function IR symbols (from other source files in the module) require a parent facade class to be
                    // generated, which requires a container source to be provided. Without a facade class, function IR symbols will have
                    // an `IrExternalPackageFragment` parent, which trips up code generation during IR lowering.
                    val psiSourceFile =
                        descriptor.toSourceElement.containingFile as? PsiSourceFile ?: return super.getContainerSource(descriptor)
                    return FacadeClassSourceShimForFragmentCompilation(psiSourceFile)
                }
            }
        } else {
            JvmGeneratorExtensionsImpl(compilerConfiguration)
        }

        val ideCodegenSettings = JvmIrCodegenFactory.IdeCodegenSettings(
          shouldStubAndNotLinkUnboundSymbols = stubUnboundIrSymbols,
          shouldDeduplicateBuiltInSymbols = stubUnboundIrSymbols,

          // Because the file to compile may be contained in a "common" multiplatform module, an `expect` declaration doesn't necessarily
          // have an obvious associated `actual` symbol. `shouldStubOrphanedExpectSymbols` generates stubs for such `expect` declarations.
          shouldStubOrphanedExpectSymbols = true,

          // Likewise, the file to compile may be contained in a "platform" multiplatform module, where the `actual` declaration is
          // referenced in the symbol table automatically, but not its `expect` counterpart, because it isn't contained in the files to
          // compile. `shouldReferenceUndiscoveredExpectSymbols` references such `expect` symbols in the symbol table so that they can
          // subsequently be stubbed.
          shouldReferenceUndiscoveredExpectSymbols = true,
        )

        return JvmIrCodegenFactory(
            compilerConfiguration,
            PhaseConfig(jvmPhases),
            jvmGeneratorExtensions = jvmGeneratorExtensions,
            ideCodegenSettings = ideCodegenSettings,
        )
    }
}