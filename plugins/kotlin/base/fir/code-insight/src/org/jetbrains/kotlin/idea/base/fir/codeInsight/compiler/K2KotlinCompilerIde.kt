// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.fir.codeInsight.compiler

import org.jetbrains.kotlin.analysis.low.level.api.fir.compiler.LLCompilerFacade
import org.jetbrains.kotlin.diagnostics.KtDiagnostic
import org.jetbrains.kotlin.idea.base.codeInsight.compiler.CompilationOptions
import org.jetbrains.kotlin.idea.base.codeInsight.compiler.CompilationResult
import org.jetbrains.kotlin.idea.base.codeInsight.compiler.KotlinCompilerIde
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.base.projectStructure.languageVersionSettings
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.jvm.isJvm

internal class K2KotlinCompilerIde : KotlinCompilerIde {
    override fun compile(options: CompilationOptions): Result<CompilationResult> {
        val file = options.file

        val platform = file.platform
        if (!platform.isCommon() && !platform.isJvm()) {
            throw IllegalArgumentException("Only JVM and Common platforms are supported")
        }

        return LLCompilerFacade.compile(file, options.configuration, file.languageVersionSettings, options.classBuilderFactory)
            .map { result ->
                val ktDiagnostics = result.diagnostics.map { ktPsiDiagnostic -> ktPsiDiagnostic as KtDiagnostic }
                CompilationResult(result.outputFiles, ktDiagnostics)
            }
    }
}