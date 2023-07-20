// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.fir.extensions

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.kotlin.analysis.project.structure.KtModule
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.nio.file.Path

interface CompilerPluginExtension {

  companion object {
    val EP_NAME: ExtensionPointName<CompilerPluginExtension> =
      ExtensionPointName.create("org.jetbrains.kotlin.compilerPluginExtension")
  }

  fun pluginClassPath(userSuppliedPluginJar: Path) : Path?
  fun pluginClassPath(module: KtModule): Path?
  fun configuration(): CompilerConfiguration?
}