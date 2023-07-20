// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.fir.extensions

import com.intellij.ide.impl.isTrusted
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.runAndLogException
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import com.intellij.openapi.util.registry.RegistryValueListener
import com.intellij.util.concurrency.SynchronizedClearableLazy
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.orNull
import com.intellij.util.lang.UrlClassLoader
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.storage.EntityChange
import com.intellij.platform.workspace.jps.entities.FacetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.analysis.project.structure.KtCompilerPluginsProvider
import org.jetbrains.kotlin.analysis.project.structure.KtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.KtModule
import org.jetbrains.kotlin.analysis.project.structure.KtSourceModule
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.plugins.processCompilerPluginsOptions
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.extensions.ProjectExtensionDescriptor
import org.jetbrains.kotlin.fir.extensions.FirAssignExpressionAltererExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.idea.base.projectStructure.ideaModule
import org.jetbrains.kotlin.idea.base.projectStructure.moduleInfo
import org.jetbrains.kotlin.idea.base.projectStructure.moduleInfo.ModuleTestSourceInfo
import org.jetbrains.kotlin.idea.base.util.Frontend10ApiUsage
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettingsListener
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.util.ServiceLoaderLite
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentMap
import kotlin.io.path.*

private data class CompilerExtensionPathToConfiguration(val path:Path, val compilerConfiguration: CompilerConfiguration? = null)
@OptIn(ExperimentalCompilerApi::class)
internal class KtCompilerPluginsProviderIdeImpl(private val project: Project, cs: CoroutineScope) : KtCompilerPluginsProvider(), Disposable {
    private val pluginsCacheCachedValue: SynchronizedClearableLazy<PluginsCache?> = SynchronizedClearableLazy { createNewCache() }
    private val pluginsCache: PluginsCache?
        get() = pluginsCacheCachedValue.value

    private val onlyBundledPluginsEnabledRegistryValue: RegistryValue =
        Registry.get("kotlin.k2.only.bundled.compiler.plugins.enabled")

    private val onlyBundledPluginsEnabled: Boolean
        get() = onlyBundledPluginsEnabledRegistryValue.asBoolean()

    init {
        cs.launch {
            WorkspaceModel.getInstance(project).changesEventFlow.collect { event ->
                val hasChanges = event.getChanges(FacetEntity::class.java).any { change ->
                    change.facetTypes.any { it == KotlinFacetType.ID }
                }
                if (hasChanges) {
                    pluginsCacheCachedValue.drop()
                }
            }
        }
        val messageBusConnection = project.messageBus.connect(this)
        messageBusConnection.subscribe(KotlinCompilerSettingsListener.TOPIC,
            object : KotlinCompilerSettingsListener {
                override fun <T> settingsChanged(oldSettings: T?, newSettings: T?) {
                    pluginsCacheCachedValue.drop()
                }
            }
        )

        onlyBundledPluginsEnabledRegistryValue.addListener(
            object : RegistryValueListener {
                override fun afterValueChanged(value: RegistryValue) {
                    pluginsCacheCachedValue.drop()
                }
            },
            this
        )
    }

    private val EntityChange<FacetEntity>.facetTypes: List<String>
        get() = when (this) {
            is EntityChange.Added -> listOf(entity.facetType)
            is EntityChange.Removed -> listOf(entity.facetType)
            is EntityChange.Replaced -> listOf(oldEntity.facetType, newEntity.facetType)
        }

    private fun createNewCache(): PluginsCache? {
        if (!project.isTrusted()) return null
        val pluginsClassLoader: UrlClassLoader = UrlClassLoader.build().apply {
            parent(KtSourceModule::class.java.classLoader)
            val pluginsClasspath = ModuleManager.getInstance(project).modules
                .flatMap { it.getCompilerArguments().getSubstitutedPluginClassPaths().map { it.path } }
                .distinct()
            files(pluginsClasspath)
        }.get()
        return PluginsCache(
            pluginsClassLoader,
            ContainerUtil.createConcurrentWeakMap<KtModule, Optional<CompilerPluginRegistrar.ExtensionStorage>>()
        )
    }

    private class PluginsCache(
        val pluginsClassLoader: UrlClassLoader,
        val registrarForModule: ConcurrentMap<KtModule, Optional<CompilerPluginRegistrar.ExtensionStorage>>
    )

    override fun <T : Any> getRegisteredExtensions(module: KtModule, extensionType: ProjectExtensionDescriptor<T>): List<T> {
        val registrarForModule = pluginsCache?.registrarForModule ?: return emptyList()
        val extensionStorage = registrarForModule.computeIfAbsent(module) {
            Optional.ofNullable(when (it) {
                is KtSourceModule -> computeExtensionStorage(it)
                is KtLibraryModule -> computeExtensionLibraryStorage(it)
                else -> null

            })
        }.orNull() ?: return emptyList()
        val registrars = extensionStorage.registeredExtensions[extensionType] ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return registrars as List<T>
    }

    private fun computeExtensionLibraryStorage(module: KtLibraryModule): CompilerPluginRegistrar.ExtensionStorage? {

        val (classpath, configuration) = CompilerPluginExtension.EP_NAME.extensions
          .asSequence()
          .mapNotNull { ext ->
              ext.pluginClassPath(module)?.let { it to ext.configuration() }
          }.firstOrNull() ?: return null
        val classLoader = pluginsCache?.pluginsClassLoader ?: return null
        val logger = logger<KtCompilerPluginsProviderIdeImpl>()
        val pluginRegistrars =
          logger.runAndLogException {
              ServiceLoaderLite.loadImplementations<CompilerPluginRegistrar>(
                listOf(classpath.toFile()), classLoader)
          }
            ?.takeIf { it.isNotEmpty() }
          ?: return null

        val storage = CompilerPluginRegistrar.ExtensionStorage()
        for (pluginRegistrar in pluginRegistrars) {
            with(pluginRegistrar) {
                try {
                    val appliedConfiguration = configuration?: CompilerConfiguration()
                    storage.registerExtensions(appliedConfiguration.apply {
                        putIfAbsent(JVMConfigurationKeys.IR, true)
                        putIfAbsent(CommonConfigurationKeys.USE_FIR, true)
                    })
                }
                catch (e: ProcessCanceledException) {
                    throw e
                }
                catch (e: Throwable) {
                    LOG.error(e)
                }
            }
        }
        return storage
    }

    override fun isPluginOfTypeRegistered(module: KtModule, pluginType: CompilerPluginType): Boolean {
        val extension = when (pluginType) {
            CompilerPluginType.ASSIGNMENT -> FirAssignExpressionAltererExtension::class
            else -> return false
        }

        return getRegisteredExtensions(module, FirExtensionRegistrarAdapter)
            .map { (it as FirExtensionRegistrar).configure() }
            .any { it.extensions[extension]?.isNotEmpty() == true }
    }

    @OptIn(Frontend10ApiUsage::class)
    private fun computeExtensionStorage(module: KtSourceModule): CompilerPluginRegistrar.ExtensionStorage? {
        val classLoader = pluginsCache?.pluginsClassLoader ?: return null
        val compilerArguments = module.ideaModule.getCompilerArguments()
        val (classPaths, configurations) = compilerArguments.getSubstitutedPluginClassPaths().map {
            it.path.toFile() to it.compilerConfiguration
        }.unzip().takeIf { it.first.isNotEmpty() } ?: return null

        val logger = logger<KtCompilerPluginsProviderIdeImpl>()

        val pluginRegistrars =
            logger.runAndLogException { ServiceLoaderLite.loadImplementations<CompilerPluginRegistrar>(classPaths, classLoader) }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val commandLineProcessors = logger.runAndLogException {
            ServiceLoaderLite.loadImplementations<CommandLineProcessor>(classPaths, classLoader)
        } ?: return null

        val compilerConfiguration = CompilerConfiguration().apply {
            // Temporary work-around for KTIJ-24320. Calls to 'setupCommonArguments()' and 'setupJvmSpecificArguments()'
            // (or even a platform-agnostic alternative) should be added.
            if (compilerArguments is K2JVMCompilerArguments) {
                val compilerExtension = CompilerModuleExtension.getInstance(module.ideaModule)
                val outputUrl = when (module.moduleInfo) {
                    is ModuleTestSourceInfo -> compilerExtension?.compilerOutputUrlForTests
                    else -> compilerExtension?.compilerOutputUrl
                }

                putIfNotNull(JVMConfigurationKeys.JVM_TARGET, compilerArguments.jvmTarget?.let(JvmTarget::fromString))
                putIfNotNull(JVMConfigurationKeys.OUTPUT_DIRECTORY, outputUrl?.let { File(it) })
                put(JVMConfigurationKeys.IR, true) // FIR cannot work with the old backend
                put(CommonConfigurationKeys.USE_FIR, true)
            }

            processCompilerPluginsOptions(this, compilerArguments.pluginOptions?.toList(), commandLineProcessors)
        }

        val storage = CompilerPluginRegistrar.ExtensionStorage()
        pluginRegistrars.forEachIndexed { index, pluginRegistrar ->
            with(pluginRegistrar) {
                try {
                    val appliedConfiguration = configurations[index]?.apply {
                        putIfAbsent(JVMConfigurationKeys.IR, true) // FIR cannot work with the old backend
                        putIfAbsent(CommonConfigurationKeys.USE_FIR, true)
                    } ?: compilerConfiguration
                    storage.registerExtensions(appliedConfiguration)
                }
                catch (e : ProcessCanceledException) {
                    throw e
                }
                catch (e: Throwable) {
                    LOG.error(e)
                }
            }
        }
        return storage
    }

    private fun CommonCompilerArguments.getOriginalPluginClassPaths(): List<Path> {
        return this
            .pluginClasspaths
            ?.map { Path.of(it).toAbsolutePath() }
            ?.toList()
            .orEmpty()
    }

    private fun CommonCompilerArguments.getSubstitutedPluginClassPaths(): List<CompilerExtensionPathToConfiguration> {
        val userDefinedPlugins = getOriginalPluginClassPaths()
        return userDefinedPlugins.mapNotNull(::substitutePluginJar)
    }

    /**
     * We have the following logic for plugins' substitution:
     * 1. Always replace our own plugins (like "allopen", "noarg", etc.) with bundled ones to avoid binary incompatibility.
     * 2. Allow to use other compiler plugins only if [onlyBundledPluginsEnabled] is set to false; otherwise, filter them.
     */
    private fun substitutePluginJar(userSuppliedPluginJar: Path): CompilerExtensionPathToConfiguration? {
        val bundledPlugin = KotlinK2BundledCompilerPlugins.findCorrespondingBundledPlugin(userSuppliedPluginJar)
        if (bundledPlugin != null) return CompilerExtensionPathToConfiguration(bundledPlugin.bundledJarLocation)

        return CompilerPluginExtension.EP_NAME.extensions.asSequence().mapNotNull{ pluginExt ->
            pluginExt.pluginClassPath(userSuppliedPluginJar)
              ?.let { CompilerExtensionPathToConfiguration(it, pluginExt.configuration()) }
        }.firstOrNull() ?: CompilerExtensionPathToConfiguration(userSuppliedPluginJar).takeUnless { onlyBundledPluginsEnabled }
    }


    private fun Module.getCompilerArguments(): CommonCompilerArguments {
        return KotlinFacet.get(this)?.configuration?.settings?.mergedCompilerArguments
            ?: KotlinCommonCompilerArgumentsHolder.getInstance(project).settings
    }

    override fun dispose() {
        pluginsCacheCachedValue.drop()
    }

    companion object {
        fun getInstance(project: Project): KtCompilerPluginsProviderIdeImpl {
            return project.getService(KtCompilerPluginsProvider::class.java) as KtCompilerPluginsProviderIdeImpl
        }
        private val LOG = logger<KtCompilerPluginsProviderIdeImpl>()
    }
}
