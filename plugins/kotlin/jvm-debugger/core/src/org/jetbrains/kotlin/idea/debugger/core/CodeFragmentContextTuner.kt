// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parents
import com.sun.jdi.Location
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

interface CodeFragmentContextTuner {
    fun tuneContextElement(element: PsiElement?, location: Location?): PsiElement?

    companion object {
        fun getInstance(): CodeFragmentContextTuner {
            return ApplicationManager.getApplication().getService(CodeFragmentContextTuner::class.java)
        }
    }
}

internal class K2CodeFragmentContextTunerImpl : CodeFragmentContextTuner {
    override fun tuneContextElement(element: PsiElement?, location: Location?): PsiElement? {
        return when (element) {
            null -> return null
            is PsiCodeBlock -> tuneContextElement(element.context?.context, location)
            is KtLightClass -> tuneContextElement(element.kotlinOrigin, location)
            else -> {
                when (val containingFile = element.containingFile) {
                    is PsiJavaFile -> element
                    is KtFile -> tuneKotlinContextElement(getElementSkippingWhitespaces(element), containingFile, location)
                    else -> null
                }
            }
        }
    }

    private fun tuneKotlinContextElement(element: PsiElement, containingFile: KtFile, location: Location?): PsiElement {
        if (element is LeafPsiElement && element.elementType == KtTokens.RBRACE) {
            val parent = element.parent

            val containingClass = (parent as? KtClassBody)?.parent as? KtClassOrObject
            if (containingClass != null) {
                val containingClassOrLiteral = containingClass.parent?.takeIf { it is KtObjectLiteralExpression }
                return tuneKotlinContextElement(containingClassOrLiteral ?: containingClass, containingFile, location)
            }

            if (parent is KtBlockExpression) {
                // Block scope should be visible on a closing bracket, so we cannot lose the precise context element
                return element
            }
        }

        val editorTextProvider = KotlinEditorTextProvider.instance

        var result = element.parents(withSelf = true)
            .filterIsInstance<KtElement>()
            .firstOrNull { editorTextProvider.isAcceptedAsCodeFragmentContext(it) }

        val isInsideConstructor = location?.safeMethod()?.isConstructor == true

        if (isInsideConstructor) {
            if (result is KtClass) {
                result = result.primaryConstructor ?: result
            }
        } else if (result is KtPrimaryConstructor) {
            result = result.containingClassOrObject ?: result
        }

        return result ?: containingFile
    }

    private fun getElementSkippingWhitespaces(element: PsiElement): PsiElement {
        // elementAt can be PsiWhiteSpace when codeFragment is created from line start offset (in case of first opening EE window)
        if (element is PsiWhiteSpace || element is PsiComment) {
            val newElement = PsiTreeUtil.skipSiblingsForward(element, PsiWhiteSpace::class.java, PsiComment::class.java)
            if (newElement != null) {
                return newElement
            }
        }

        return element
    }
}

internal class K1CodeFragmentContextTuner : CodeFragmentContextTuner {
    override fun tuneContextElement(element: PsiElement?, location: Location?): PsiElement? {
        if (element == null) return null

        if (element is PsiCodeBlock) {
            return tuneContextElement(element.context?.context, location)
        }

        if (element is KtLightClass) {
            return tuneContextElement(element.kotlinOrigin, location)
        }

        val containingFile = element.containingFile
        if (containingFile is PsiJavaFile) {
            return element
        }

        if (containingFile !is KtFile) {
            return null
        }

        val accurateElement = getAccurateContextElement(element, containingFile)
        if (accurateElement != null) {
            return accurateElement
        }

        return containingFile
    }

    private fun getAccurateContextElement(elementAt: PsiElement, containingFile: KtFile): PsiElement? {
        // elementAt can be PsiWhiteSpace when codeFragment is created from line start offset (in case of first opening EE window)
        val elementAtSkippingWhitespaces = getElementSkippingWhitespaces(elementAt)

        if (elementAtSkippingWhitespaces is LeafPsiElement && elementAtSkippingWhitespaces.elementType == KtTokens.RBRACE) {
            val classBody = elementAtSkippingWhitespaces.parent as? KtClassBody
            val classOrObject = classBody?.parent as? KtClassOrObject
            var declarationParent = classOrObject?.parent
            if (declarationParent is KtObjectLiteralExpression) {
                declarationParent = declarationParent.parent
            }

            if (declarationParent != null) {
                return getAccurateContextElement(declarationParent, containingFile)
            }
        }

        val lineStartOffset = elementAtSkippingWhitespaces.textOffset

        val targetExpression = PsiTreeUtil.findElementOfClassAtOffset(containingFile, lineStartOffset, KtExpression::class.java, false)

        val editorTextProvider = KotlinEditorTextProvider.instance

        if (targetExpression != null) {
            if (editorTextProvider.isAcceptedAsCodeFragmentContext(targetExpression)) {
                return targetExpression
            }

            editorTextProvider.findEvaluationTarget(elementAt, true)?.let { return it }

            targetExpression.parents(withSelf = false)
                .firstOrNull { editorTextProvider.isAcceptedAsCodeFragmentContext(it) }
                ?.let { return it }
        }

        return null
    }

    private fun getElementSkippingWhitespaces(elementAt: PsiElement): PsiElement {
        if (elementAt is PsiWhiteSpace || elementAt is PsiComment) {
            val newElement = PsiTreeUtil.skipSiblingsForward(elementAt, PsiWhiteSpace::class.java, PsiComment::class.java)
            if (newElement != null) {
                return newElement
            }
        }

        return elementAt
    }
}