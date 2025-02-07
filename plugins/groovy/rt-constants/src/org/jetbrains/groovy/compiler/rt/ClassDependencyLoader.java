// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.groovy.compiler.rt;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.HashSet;
import java.util.Set;

public class ClassDependencyLoader {
  private final Set<Class<?>> myVisited = new HashSet<Class<?>>();

  /**
   * @throws ClassNotFoundException when any of the classes can't be loaded, that's referenced in aClass' fields, methods etc. recursively
   */
  public Class loadDependencies(Class aClass) throws ClassNotFoundException {
    loadClassDependencies(aClass);
    return aClass;
  }

  private void loadTypeDependencies(Type aClass) throws ClassNotFoundException {
    if (aClass instanceof Class) {
      loadClassDependencies((Class)aClass);
    }
    else if (aClass instanceof ParameterizedType) {
      loadTypeDependencies(((ParameterizedType)aClass).getOwnerType());
      for (Type type : ((ParameterizedType)aClass).getActualTypeArguments()) {
        loadTypeDependencies(type);
      }
    }
    else if (aClass instanceof WildcardType) {
      for (Type type : ((WildcardType)aClass).getLowerBounds()) {
        loadTypeDependencies(type);
      }
      for (Type type : ((WildcardType)aClass).getUpperBounds()) {
        loadTypeDependencies(type);
      }
    }
    else if (aClass instanceof GenericArrayType) {
      loadTypeDependencies(((GenericArrayType)aClass).getGenericComponentType());
    }
  }

  protected void loadClassDependencies(Class aClass) throws ClassNotFoundException {
    String name = aClass.getName();
    if (myVisited.add(aClass)) {
      try {
        for (Method method : aClass.getDeclaredMethods()) {
          loadTypeDependencies(method.getGenericReturnType());
          for (Type type : method.getGenericExceptionTypes()) {
            loadTypeDependencies(type);
          }
          for (Type type : method.getGenericParameterTypes()) {
            loadTypeDependencies(type);
          }
          for (Annotation[] annotations : method.getParameterAnnotations()) {
            loadAnnotationAttributes(annotations);
          }
        }
        for (Constructor method : aClass.getDeclaredConstructors()) {
          for (Type type : method.getGenericExceptionTypes()) {
            loadTypeDependencies(type);
          }
          for (Type type : method.getGenericParameterTypes()) {
            loadTypeDependencies(type);
          }
          for (Annotation[] annotations : method.getParameterAnnotations()) {
            loadAnnotationAttributes(annotations);
          }
        }

        for (Field field : aClass.getDeclaredFields()) {
          loadTypeDependencies(field.getGenericType());
          loadAnnotationAttributes(field.getDeclaredAnnotations());
        }

        Type superclass = aClass.getGenericSuperclass();
        if (superclass != null) {
          loadClassDependencies(aClass);
        }

        for (Type intf : aClass.getGenericInterfaces()) {
          loadTypeDependencies(intf);
        }

        loadAnnotationAttributes(aClass.getAnnotations());
        Package aPackage = aClass.getPackage();
        if (aPackage != null) {
          loadAnnotationAttributes(aPackage.getAnnotations());
        }
      }
      catch (Error e) {
        myVisited.remove(aClass);
        //noinspection InstanceofCatchParameter
        if (e instanceof LinkageError) {
          throw new ClassNotFoundException(name, e);
        }
        throw e;
      }
      catch (RuntimeException e) {
        myVisited.remove(aClass);
        //noinspection InstanceofCatchParameter
        if (e instanceof TypeNotPresentException) {
          throw new ClassNotFoundException(name, e);
        }
        throw e;
      }
    }
  }

  private void loadAnnotationAttributes(Annotation[] annotations) throws ClassNotFoundException {
    for (Annotation annotation : annotations) {
      loadAnnotationAttributes(annotation);
    }
  }

  private void loadAnnotationAttributes(Annotation annotation) throws ClassNotFoundException {
    Class<? extends Annotation> annotationType = annotation.annotationType();
    loadClassDependencies(annotationType);
    Method[] methods = annotationType.getDeclaredMethods();
    for (Method method : methods) {
      try {
        method.invoke(annotation);
      }
      catch (IllegalAccessException e) {
        // for example internal jdk annotations -> do nothing
      }
      catch (InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof TypeNotPresentException) {
          throw (TypeNotPresentException)cause;
        }
        throw new RuntimeException(e);
      }
    }
  }
}
