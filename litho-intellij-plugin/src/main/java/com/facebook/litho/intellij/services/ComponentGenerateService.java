/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.intellij.services;

import com.facebook.litho.annotations.LayoutSpec;
import com.facebook.litho.intellij.LithoPluginUtils;
import com.facebook.litho.intellij.PsiSearchUtils;
import com.facebook.litho.intellij.file.ComponentScope;
import com.facebook.litho.specmodels.internal.RunMode;
import com.facebook.litho.specmodels.model.LayoutSpecModel;
import com.facebook.litho.specmodels.model.SpecModel;
import com.facebook.litho.specmodels.processor.PsiLayoutSpecModelFactory;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class helping to create {@link LayoutSpecModel}s from the given file and update generated
 * Component files with the new model.
 */
public class ComponentGenerateService {
  private static final Logger LOG = Logger.getInstance(ComponentGenerateService.class);
  public static final Key<SpecModel> KEY_SPEC_MODEL =
      Key.create("com.facebook.litho.intellij.generation.SpecModel");
  private static final PsiLayoutSpecModelFactory MODEL_FACTORY = new PsiLayoutSpecModelFactory();
  private final Set<SpecUpdateNotifier> listeners = Collections.synchronizedSet(new HashSet<>());
  private final Project project;

  /** Subscribes listener to Spec model updates. Removes subscription once parent is disposed. */
  public void subscribe(SpecUpdateNotifier listener, Disposable parent) {
    listeners.add(listener);
    Disposer.register(parent, () -> listeners.remove(listener));
  }

  public interface SpecUpdateNotifier {
    void onSpecModelUpdated(PsiClass specCls);
  }

  public static ComponentGenerateService getInstance(Project project) {
    return ServiceManager.getService(project, ComponentGenerateService.class);
  }

  private ComponentGenerateService(Project project) {
    this.project = project;
  }

  /**
   * Updates generated Component file from the given Spec class and shows success notification. Or
   * do nothing if provided class doesn't contain {@link LayoutSpec}.
   *
   * @param layoutSpecCls class containing {@link LayoutSpec} class.
   */
  public void updateLayoutComponentAsync(PsiClass layoutSpecCls) {
    final Project project = layoutSpecCls.getProject();
    final Runnable job =
        () -> {
          final PsiClass component = updateLayoutComponentSync(layoutSpecCls);
          if (component != null) {
            showSuccess(component.getName(), project);
          }
        };
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      job.run();
    } else {
      DumbService.getInstance(project).smartInvokeLater(job);
    }
  }

  @Nullable
  public PsiClass updateLayoutComponentSync(PsiClass layoutSpecCls) {
    final String componentQN =
        LithoPluginUtils.getLithoComponentNameFromSpec(layoutSpecCls.getQualifiedName());
    if (componentQN == null) return null;

    final LayoutSpecModel model = createLayoutModel(layoutSpecCls);
    if (model == null) return null;

    final Project project = layoutSpecCls.getProject();
    final PsiClass generatedComponent = updateComponent(componentQN, model, project);
    if (generatedComponent == null) return null;

    layoutSpecCls.putUserData(KEY_SPEC_MODEL, model);
    Set<SpecUpdateNotifier> copy;
    synchronized (listeners) {
      copy = new HashSet<>(listeners);
    }
    copy.forEach(listener -> listener.onSpecModelUpdated(layoutSpecCls));
    return generatedComponent;
  }

  /** Updates generated Component file from the given Spec model. */
  @Nullable
  public static PsiClass updateComponent(
      String componentQualifiedName, SpecModel model, Project project) {
    final String componentShortName = StringUtil.getShortName(componentQualifiedName);
    if (componentShortName.isEmpty()) return null;

    final Optional<PsiClass> generatedClass =
        Optional.ofNullable(PsiSearchUtils.findOriginalClass(project, componentQualifiedName))
            .filter(cls -> !ComponentScope.contains(cls.getContainingFile()));
    final boolean isPresent = generatedClass.isPresent();
    final String newContent = createFileContentFromModel(componentQualifiedName, model);
    if (isPresent) {
      final Document document =
          PsiDocumentManager.getInstance(project)
              .getDocument(generatedClass.get().getContainingFile());
      if (document != null) {
        // Write access is allowed inside write-action only
        WriteAction.run(
            () -> {
              document.setText(newContent);
            });
        FileDocumentManager.getInstance().saveDocument(document);
        return generatedClass.get();
      }
    } else {
      final PsiFile file =
          PsiFileFactory.getInstance(project)
              .createFileFromText(componentShortName + ".java", StdFileTypes.JAVA, newContent);
      ComponentScope.include(file);
      final PsiClass inMemory =
          LithoPluginUtils.getFirstClass(file, cls -> componentShortName.equals(cls.getName()))
              .orElse(null);
      if (inMemory == null) return null;

      ComponentsCacheService.getInstance(project).update(componentQualifiedName, inMemory);
      return inMemory;
    }
    return null;
  }

  private static void showSuccess(String componentName, Project project) {
    LithoPluginUtils.showInfo(componentName + " was regenerated", project);
  }

  /**
   * Generates new {@link LayoutSpecModel} from the given {@link PsiClass}.
   *
   * @return new {@link LayoutSpecModel} or null if provided class is not a {@link
   *     com.facebook.litho.annotations.LayoutSpec} class.
   */
  @Nullable
  public static LayoutSpecModel createLayoutModel(PsiClass layoutSpecCls) {
    return MODEL_FACTORY.createWithPsi(layoutSpecCls.getProject(), layoutSpecCls, null);
  }

  private static String createFileContentFromModel(String clsQualifiedName, SpecModel specModel) {
    TypeSpec typeSpec = specModel.generate(RunMode.normal());
    return JavaFile.builder(StringUtil.getPackageName(clsQualifiedName), typeSpec)
        .skipJavaLangImports(true)
        .build()
        .toString();
  }
}
