// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.processors;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.persistence.AttributeConverter;

/** Abstract processor to generate {@link AttributeConverter} for VKey type. */
public abstract class AbstractVKeyProcessor extends AbstractProcessor {

  private static final String VKEY_FULLY_QUALIFIED_NAME = "google.registry.persistence.VKey";
  private static final String CONVERTER_CLASS_NAME_TEMP = "VKeyConverter_%s";
  // The method with same name should be defined in StringVKey and LongVKey
  private static final String CLASS_NAME_SUFFIX_KEY = "classNameSuffix";

  abstract Class<?> getSqlColumnType();

  abstract String getAnnotationSimpleName();

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    getElementUtils().getTypeElement(VKEY_FULLY_QUALIFIED_NAME);
    annotations.forEach(
        vKeyAnnotationType -> {
          ElementFilter.fieldsIn(roundEnv.getElementsAnnotatedWith(vKeyAnnotationType))
              .forEach(
                  annotatedFieldElement -> {
                    DeclaredType vKeyDeclaredType = getDeclaredType(annotatedFieldElement);
                    // It is possible the type of the annotated field is Set<VKey<Entity>>, so we
                    // need to get its type argument which is what we want.
                    if (!isVKeyType(vKeyDeclaredType)) {
                      vKeyDeclaredType = getTypeArgument(annotatedFieldElement);
                    }

                    checkState(
                        isVKeyType(vKeyDeclaredType),
                        String.format(
                            "%s cannot be annotated on a %s field",
                            vKeyAnnotationType.getQualifiedName(),
                            getDeclaredType(annotatedFieldElement).toString()));

                    TypeMirror entityType = getTypeArgument(vKeyDeclaredType);
                    List<AnnotationMirror> actualAnnotation =
                        annotatedFieldElement.getAnnotationMirrors().stream()
                            .filter(
                                annotationType ->
                                    annotationType
                                        .getAnnotationType()
                                        .asElement()
                                        .equals(vKeyAnnotationType))
                            .collect(toImmutableList());
                    checkState(
                        actualAnnotation.size() == 1,
                        String.format(
                            "field can have only 1 %s annotation", getAnnotationSimpleName()));
                    String converterClassNameSuffix =
                        actualAnnotation.get(0).getElementValues().entrySet().stream()
                            .filter(
                                entry ->
                                    entry
                                        .getKey()
                                        .getSimpleName()
                                        .toString()
                                        .equals(CLASS_NAME_SUFFIX_KEY))
                            .map(entry -> ((String) entry.getValue().getValue()).trim())
                            .findFirst()
                            .orElse("");
                    if (converterClassNameSuffix.isEmpty()) {
                      converterClassNameSuffix =
                          getTypeUtils().asElement(entityType).getSimpleName().toString();
                    }

                    try {
                      createJavaFile(
                              getPackageName(annotatedFieldElement),
                              String.format(CONVERTER_CLASS_NAME_TEMP, converterClassNameSuffix),
                              vKeyDeclaredType,
                              entityType)
                          .writeTo(processingEnv.getFiler());
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
        });
    return false;
  }

  private JavaFile createJavaFile(
      String packageName,
      String converterClassName,
      TypeMirror vKeyTypeMirror,
      TypeMirror entityTypeMirror) {
    ParameterizedTypeName vKey = (ParameterizedTypeName) ParameterizedTypeName.get(vKeyTypeMirror);
    TypeName entityType = ClassName.get(entityTypeMirror);

    ParameterizedTypeName attributeConverter =
        ParameterizedTypeName.get(
            ClassName.get(AttributeConverter.class), vKey, ClassName.get(getSqlColumnType()));

    MethodSpec convertToDatabaseColumn =
        MethodSpec.methodBuilder("convertToDatabaseColumn")
            .addAnnotation(Override.class)
            .addAnnotation(Nullable.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(getSqlColumnType())
            .addParameter(vKey, "attribute")
            .addStatement(
                "return attribute == null ? null : ($T) attribute.getSqlKey()", getSqlColumnType())
            .build();

    MethodSpec convertToEntityAttribute =
        MethodSpec.methodBuilder("convertToEntityAttribute")
            .addAnnotation(Override.class)
            .addAnnotation(Nullable.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(vKey)
            .addParameter(getSqlColumnType(), "dbData")
            .addStatement(
                "return dbData == null ? null : $T.createSql($T.class, dbData)",
                vKey.rawType,
                entityType)
            .build();

    TypeSpec vKeyConverter =
        TypeSpec.classBuilder(converterClassName)
            .addModifiers(Modifier.FINAL)
            .addSuperinterface(attributeConverter)
            .addMethod(convertToDatabaseColumn)
            .addMethod(convertToEntityAttribute)
            .build();

    return JavaFile.builder(packageName, vKeyConverter).build();
  }

  private DeclaredType getDeclaredType(Element element) {
    checkState(element.asType().getKind() == TypeKind.DECLARED, "element is not a DeclaredType");
    return (DeclaredType) element.asType();
  }

  private boolean isVKeyType(DeclaredType declaredType) {
    TypeElement vKeyTypeElement = getElementUtils().getTypeElement(VKEY_FULLY_QUALIFIED_NAME);
    return vKeyTypeElement.equals(getTypeUtils().asElement(declaredType));
  }

  private DeclaredType getTypeArgument(Element element) {
    return getTypeArgument(getDeclaredType(element));
  }

  private DeclaredType getTypeArgument(DeclaredType declaredType) {
    checkState(
        declaredType.getTypeArguments().size() == 1, "annotated type must have one type parameter");
    TypeMirror typeMirror = declaredType.getTypeArguments().get(0);
    if (typeMirror.getKind() == TypeKind.DECLARED) {
      return (DeclaredType) typeMirror;
    } else if (typeMirror.getKind() == TypeKind.WILDCARD) {
      return (DeclaredType) ((WildcardType) typeMirror).getExtendsBound();
    } else {
      throw new IllegalArgumentException(
          String.format("unsupported type argument %s", declaredType.toString()));
    }
  }

  private String getPackageName(Element element) {
    return getElementUtils().getPackageOf(element).getQualifiedName().toString();
  }

  private Elements getElementUtils() {
    return processingEnv.getElementUtils();
  }

  private Types getTypeUtils() {
    return processingEnv.getTypeUtils();
  }
}
