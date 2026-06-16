/*
 * GPLv3 License
 *
 * Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 *
 * This project is distributed under the GPLv3 license.
 *
 * https://github.com/jar-analyzer/jar-analyzer/blob/master/LICENSE
 */

package me.n1ar4.jar.analyzer.core;

import me.n1ar4.jar.analyzer.core.reference.ClassReference;
import me.n1ar4.jar.analyzer.core.reference.MethodReference;
import me.n1ar4.jar.analyzer.entity.ClassFileEntity;

import java.util.*;

public class AnalyzeEnv {
    public static Set<ClassFileEntity> classFileList = new HashSet<>();
    public static final Set<ClassReference> discoveredClasses = new HashSet<>();
    public static final Set<MethodReference> discoveredMethods = new HashSet<>();
    public static final Map<ClassReference.Handle, List<MethodReference>> methodsInClassMap = new HashMap<>();
    public static final Map<ClassReference.Handle, ClassReference> classMap = new HashMap<>();
    public static final Map<MethodReference.Handle, MethodReference> methodMap = new HashMap<>();
    public static final HashMap<MethodReference.Handle,
            HashSet<MethodReference.Handle>> methodCalls = new HashMap<>();
    public static InheritanceMap inheritanceMap;
    public static Map<MethodReference.Handle, List<String>> strMap = new HashMap<>();
    public static Map<MethodReference.Handle, List<String>> stringAnnoMap = new HashMap<>();

    /**
     * Track corrupted class files with StackMapTable issues.
     * Key: jarName + "!" + classFileName, Value: exception message
     */
    public static final Set<String> corruptedFiles = new HashSet<>();
}
