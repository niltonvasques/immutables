/*
   Copyright 2015 Immutables Authors and Contributors

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.immutables.value.processor.meta;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.immutables.generator.AnnotationMirrors;
import org.immutables.generator.SourceExtraction;
import org.immutables.generator.SourceTypes;
import org.immutables.value.processor.meta.ValueAttribute.NullElements;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * Encapsulates routines and various hacks for get relevant strings for the raw types and type
 * parameters, while attempting to resolve unresolved types using source imports.
 */
class TypeStringProvider {
  private final TypeMirror startType;
  private final Element element;
  private final List<String> typeParameterStrings = Lists.newArrayListWithCapacity(2);
  private StringBuilder buffer;
  boolean unresolvedTypeHasOccured;
  boolean hasMaybeUnresolvedYetAfter;
  boolean hasTypeVariables;

  private String rawTypeName;
  private String returnTypeName;
  private boolean ended;

  private @Nullable List<String> workaroundTypeParameters;
  private @Nullable String workaroundTypeString;
  private final Reporter reporter;
  private final String[] allowedTypevars;
  private final @Nullable String[] typevarArguments;
  private final ImportsTypeStringResolver importsResolver;

  @Nullable
  String elementTypeAnnotations;

  @Nullable
  String secondaryElementTypeAnnotation;
  boolean processNestedTypeUseAnnotations;
  boolean forAttribute = false;
  NullElements nullElements = NullElements.BAN;
  boolean nullableTypeAnnotation;

  TypeStringProvider(
      Reporter reporter,
      Element element,
      TypeMirror startType,
      ImportsTypeStringResolver importsResolver,
      String[] allowedTypevars,
      @Nullable String[] typevarArguments) {

    this.reporter = reporter;
    this.startType = startType;
    this.element = element;
    this.allowedTypevars = allowedTypevars;
    this.typevarArguments = typevarArguments;
    this.importsResolver = importsResolver;
    checkArgument(typevarArguments == null || allowedTypevars.length == typevarArguments.length,
        "Element %s, mismatching type variables, allowed: %s, given: %s",
        element.getSimpleName(),
        Arrays.asList(allowedTypevars),
        typevarArguments == null ? null : Arrays.asList(typevarArguments));
  }

  String rawTypeName() {
    return rawTypeName;
  }

  String returnTypeName() {
    return returnTypeName;
  }

  boolean hasSomeUnresovedTypes() {
    return hasMaybeUnresolvedYetAfter;
  }

  ImmutableList<String> typeParameters() {
    return ImmutableList.copyOf(workaroundTypeParameters != null
        ? workaroundTypeParameters
        : typeParameterStrings);
  }

  void process() {
    if (startType.getKind().isPrimitive()) {
      // taking a shortcut for primitives
      String typeName = Ascii.toLowerCase(startType.getKind().name());
      this.rawTypeName = typeName;
      this.returnTypeName = typeName;
      List<? extends AnnotationMirror> annotations = AnnotationMirrors.from(startType);
      if (!annotations.isEmpty()) {
        returnTypeName = typeAnnotationsToBuffer(annotations).append(typeName).toString();
      }
    } else {
      this.buffer = new StringBuilder(100);
      caseType(startType);

      if (workaroundTypeString != null) {
        // to not mix the mess, we just replace buffer with workaround produced type string
        this.buffer = new StringBuilder(workaroundTypeString);
      }

      // It seems that array type annotations are not exposed in javac
      // Nested type argument's type annotations are not exposed as well (in javac)
      // So currently we instert only for top level, declared type (here),
      // and primitives (see above)
      TypeKind k = startType.getKind();
      if (k == TypeKind.DECLARED || k == TypeKind.ERROR) {
        insertTypeAnnotationsIfPresent(startType, 0, rawTypeName.length());
      }

      this.returnTypeName = buffer.toString();
    }
  }

  private void appendResolved(DeclaredType type) {
    TypeElement typeElement = (TypeElement) type.asElement();
    String typeName = typeElement.getQualifiedName().toString();

    if (unresolvedTypeHasOccured) {
      if (type == startType && forAttribute) {
        // special routine for top level type, opportunistically
        // resolving not yet generated type assumit it
        typeName = importsResolver.resolveTopForAttribute(typeName);
      } else {
        typeName = importsResolver.apply(typeName);
      }
      hasMaybeUnresolvedYetAfter |= importsResolver.unresolved;
    }

    buffer.append(typeName);
    if (startType == type) {
      rawTypeName = typeName;
    }
  }

  private void insertTypeAnnotationsIfPresent(TypeMirror type, int typeStart, int typeEnd) {
    List<? extends AnnotationMirror> annotations = AnnotationMirrors.from(type);
    if (!annotations.isEmpty()) {
      StringBuilder annotationBuffer = typeAnnotationsToBuffer(annotations);
      int insertionIndex = typeStart + buffer.substring(typeStart, typeEnd).lastIndexOf('.') + 1;
      buffer.insert(insertionIndex, annotationBuffer);
    }
  }

  private StringBuilder typeAnnotationsToBuffer(List<? extends AnnotationMirror> annotations) {
    StringBuilder annotationBuffer = new StringBuilder(100);
    for (AnnotationMirror annotationMirror : annotations) {
      CharSequence sequence = AnnotationMirrors.toCharSequence(annotationMirror, importsResolver);
      if (!nullableTypeAnnotation && sequence.toString().endsWith(EPHEMERAL_ANNOTATION_NULLABLE)) {
        this.nullableTypeAnnotation = true;
      }
      annotationBuffer
          .append(sequence)
          .append(' ');
    }
    return annotationBuffer;
  }

  private boolean tryToUseSourceAsAWorkaround() {
    if (element.getKind() != ElementKind.METHOD) {
      // we don't bother with non-method attributes
      // (like factory builder, where attributes are parameters)
      return false;
    }

    CharSequence returnTypeString = SourceExtraction.getReturnTypeString((ExecutableElement) element);
    if (returnTypeString.length() == 0) {
      // no source could be extracted for some reason, workaround will not work
      return false;
    }

    Entry<String, List<String>> extractedTypes = SourceTypes.extract(returnTypeString);

    // forces source imports based resolution,
    // we should not rely that types would be fully qualified
    Entry<String, List<String>> resolvedTypes = resolveTypes(extractedTypes);

    this.rawTypeName = resolvedTypes.getKey();
    this.workaroundTypeParameters = resolvedTypes.getValue();
    this.workaroundTypeString = SourceTypes.stringify(resolvedTypes);

    // workaround may have successed, need to continue with whatever we have
    return true;
  }

  private Entry<String, List<String>> resolveTypes(Entry<String, List<String>> sourceTypes) {
    String typeName = sourceTypes.getKey();
    typeName = importsResolver.apply(typeName);
    hasMaybeUnresolvedYetAfter |= importsResolver.unresolved;

    List<String> typeArguments = Lists.newArrayListWithCapacity(sourceTypes.getValue().size());
    for (String typeArgument : sourceTypes.getValue()) {
      String resolvedTypeArgument = SourceTypes.stringify(resolveTypes(SourceTypes.extract(typeArgument)));
      typeArguments.add(resolvedTypeArgument);
    }
    return Maps.immutableEntry(typeName, typeArguments);
  }

  void caseType(TypeMirror type) {
    if (ended) {
      // to prevent additional recursive effects when using workaround
      return;
    }
    switch (type.getKind()) {
    case ERROR:
      unresolvedTypeHasOccured = true;
      //$FALL-THROUGH$
    case DECLARED:
      DeclaredType declaredType = (DeclaredType) type;
      appendResolved(declaredType);
      appendTypeArguments(type, declaredType);
      break;
    case ARRAY:
      TypeMirror componentType = ((ArrayType) type).getComponentType();
      int mark = buffer.length();
      caseType(componentType);
      cutTypeArgument(type, mark);
      buffer.append('[').append(']');
      break;
    case WILDCARD:
      WildcardType wildcard = (WildcardType) type;
      @Nullable TypeMirror extendsBound = wildcard.getExtendsBound();
      @Nullable TypeMirror superBound = wildcard.getSuperBound();
      if (extendsBound != null) {
        buffer.append("? extends ");
        caseType(extendsBound);
      } else if (superBound != null) {
        buffer.append("? super ");
        caseType(superBound);
      } else {
        buffer.append('?');
      }
      break;
    case TYPEVAR:
      if (allowedTypevars.length != 0) {
        TypeVariable typeVariable = (TypeVariable) type;
        String var = typeVariable.toString();
        int indexOfVar = Arrays.asList(allowedTypevars).indexOf(var);
        if (indexOfVar >= 0) {
          if (typevarArguments != null) {
            buffer.append(typevarArguments[indexOfVar]);
          } else {
            hasTypeVariables = true;
            buffer.append(var);
          }
          break;
        }
        // If we don't have such parameter we consider this is the quirk
        // that was witnessed in Eclipse, we let the code below deal with it.
      }

      // this workaround breaks this recursive flow, so we set up
      // ended flag
      if (tryToUseSourceAsAWorkaround()) {
        ended = true;
        break;
      }

      reporter.withElement(element)
          .error("It is a compiler/annotation processing bug to receive type variable '%s' here."
              + " To avoid it — do not use not yet generated types in %s attribute",
              type,
              element.getSimpleName());

      // just append as toString whatever we have
      buffer.append(type);
      break;
    default:
      buffer.append(type);
    }
  }

  private void appendTypeArguments(TypeMirror type, DeclaredType declaredType) {
    List<? extends TypeMirror> arguments = declaredType.getTypeArguments();
    if (!arguments.isEmpty()) {
      buffer.append('<');
      boolean notFirst = false;
      for (TypeMirror argument : arguments) {
        typeAnnotationHandle(argument);
        if (notFirst) {
          buffer.append(',').append(' ');
        }
        notFirst = true;
        int mark = buffer.length();
        caseType(argument);
        cutTypeArgument(type, mark);
      }
      buffer.append('>');
    }
  }

  private void typeAnnotationHandle(TypeMirror argument) {
    if (!processNestedTypeUseAnnotations) {
      return;
    }
    List<? extends AnnotationMirror> annotations = AnnotationMirrors.from(argument);
    if (!annotations.isEmpty()) {
      String typeAnnotations = typeAnnotationsToBuffer(annotations).toString();
      assignElementNullness(typeAnnotations);
    }
  }

  private void assignElementNullness(String annotationString) {
    nullElements = NullElements.SKIP;
    //if (annotationString != null) {
    //  if (annotationString.contains(EPHEMERAL_ANNOTATION_NULLABLE)
    //      || annotationString.contains(EPHEMERAL_ANNOTATION_ALLOW_NULLS)) {
    //    nullElements = NullElements.ALLOW;
    //  } else if (annotationString.contains(EPHEMERAL_ANNOTATION_SKIP_NULLS)) {
    //    nullElements = NullElements.SKIP;
    //  }
    //}
  }

  private void cutTypeArgument(TypeMirror type, int mark) {
    if (startType == type) {
      typeParameterStrings.add(buffer.substring(mark));
    }
  }

  static final String EPHEMERAL_ANNOTATION_NULLABLE = "Nullable";
  static final String EPHEMERAL_ANNOTATION_ALLOW_NULLS = "AllowNulls";
  static final String EPHEMERAL_ANNOTATION_SKIP_NULLS = "SkipNulls";
}
