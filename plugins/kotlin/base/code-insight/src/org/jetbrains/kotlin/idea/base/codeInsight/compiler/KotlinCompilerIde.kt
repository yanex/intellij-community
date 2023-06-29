// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.codeInsight.compiler

import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.backend.common.output.OutputFile
import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

interface KotlinCompilerIde {
    companion object {
        fun getInstance(): KotlinCompilerIde {
            return ApplicationManager.getApplication().getService(KotlinCompilerIde::class.java)
        }
    }

    fun compile(options: CompilationOptions): Result<CompilationResult>
}

class CompilationResult(val outputFiles: List<OutputFile>, val diagnostics: List<KtDiagnostic>)

fun KotlinCompilerIde.compileToDirectory(options: CompilationOptions, destination: File): Result<CompilationResult> {
    return compile(options).onSuccess { result ->
        if (result.diagnostics.isEmpty()) {
            for (outputFile in result.outputFiles) {
                val target = File(destination, outputFile.relativePath)
                (target.parentFile ?: error("Can't find parent for file $target")).mkdirs()
                target.writeBytes(outputFile.asByteArray())
            }
        }
    }
}

fun KotlinCompilerIde.compileToJar(options: CompilationOptions, destination: File): Result<CompilationResult> {
    return compile(options).onSuccess { result ->
        if (result.diagnostics.isEmpty()) {
            destination.outputStream().buffered().use { os ->
                ZipOutputStream(os).use { zos ->
                    for (outputFile in result.outputFiles) {
                        zos.putNextEntry(ZipEntry(outputFile.relativePath))
                        zos.write(outputFile.asByteArray())
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}

/**
 * @param stubUnboundIrSymbols
 *   Whether unbound IR symbols should be stubbed instead of linked. This should be enabled if the [file]
 *   could refer to symbols defined in another file of the same module. Such symbols are not compiled (only the [file] is compiled)
 *   and so they cannot be linked from a dependency.
 *   [stubUnboundIrSymbols] only has an effect if [JVMConfigurationKeys.IR] is set to `true` in the [configuration].
 */
class CompilationOptions(
    val file: KtFile,
    val configuration: CompilerConfiguration = getDefaultCompilerConfiguration(file),
    val classBuilderFactory: ClassBuilderFactory = ClassBuilderFactories.BINARIES,
    val stubUnboundIrSymbols: Boolean = false
)

private fun getDefaultCompilerConfiguration(file: KtFile): CompilerConfiguration {
    return CompilerConfiguration().apply {
        languageVersionSettings = file.languageVersionSettings
    }
}