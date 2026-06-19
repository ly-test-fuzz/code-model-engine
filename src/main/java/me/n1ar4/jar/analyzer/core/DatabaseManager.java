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

import me.n1ar4.jar.analyzer.core.mapper.*;
import me.n1ar4.jar.analyzer.core.reference.AnnoReference;
import me.n1ar4.jar.analyzer.core.reference.ClassReference;
import me.n1ar4.jar.analyzer.core.reference.MethodReference;
import me.n1ar4.jar.analyzer.engine.log.LogManager;
import me.n1ar4.jar.analyzer.engine.log.Logger;
import me.n1ar4.jar.analyzer.engine.utils.OSUtil;
import me.n1ar4.jar.analyzer.engine.utils.PartitionUtils;
import me.n1ar4.jar.analyzer.entity.*;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.*;

/**
 * Engine version of DatabaseManager - no GUI dependency.
 * Statistics are returned/logged instead of writing to MainForm labels.
 */
public class DatabaseManager {
    private static final Logger logger = LogManager.getLogger();
    public static int PART_SIZE = 100;
    private static final SqlSession session;
    private static final ClassMapper classMapper;
    private static final MemberMapper memberMapper;
    private static final JarMapper jarMapper;
    private static final AnnoMapper annoMapper;
    private static final MethodMapper methodMapper;
    private static final StringMapper stringMapper;
    private static final InterfaceMapper interfaceMapper;
    private static final ClassFileMapper classFileMapper;
    private static final MethodImplMapper methodImplMapper;
    private static final MethodCallMapper methodCallMapper;

    private static final ClassReference notFoundClassReference = new ClassReference(
            -1, -1, null, null, null, false, null, null, "unknown", -1);

    // 统计信息（引擎构建后可以读取）
    private static int totalClassCount;
    private static int totalMethodCount;

    public static int getTotalClassCount() {
        return totalClassCount;
    }

    public static int getTotalMethodCount() {
        return totalMethodCount;
    }

    static {
        logger.info("init database");
        SqlSessionFactory factory = SqlSessionFactoryUtil.sqlSessionFactory;
        session = factory.openSession(true);
        classMapper = session.getMapper(ClassMapper.class);
        jarMapper = session.getMapper(JarMapper.class);
        annoMapper = session.getMapper(AnnoMapper.class);
        methodMapper = session.getMapper(MethodMapper.class);
        memberMapper = session.getMapper(MemberMapper.class);
        stringMapper = session.getMapper(StringMapper.class);
        classFileMapper = session.getMapper(ClassFileMapper.class);
        interfaceMapper = session.getMapper(InterfaceMapper.class);
        methodCallMapper = session.getMapper(MethodCallMapper.class);
        methodImplMapper = session.getMapper(MethodImplMapper.class);
        InitMapper initMapper = session.getMapper(InitMapper.class);
        initMapper.createJarTable();
        initMapper.createClassTable();
        initMapper.createClassFileTable();
        initMapper.createMemberTable();
        initMapper.createMethodTable();
        initMapper.createAnnoTable();
        initMapper.createInterfaceTable();
        initMapper.createMethodCallTable();
        initMapper.createMethodImplTable();
        initMapper.createStringTable();
        initMapper.createBatchMetaTable();
        // schema migration for existing DBs (ALTER TABLE is no-op if column exists via CREATE TABLE)
        try { initMapper.migrateJarTableAddHash(); } catch (Exception ignored) {}
        try { initMapper.migrateJarTableAddBatchId(); } catch (Exception ignored) {}
        try { initMapper.migrateClassFileTableAddHash(); } catch (Exception ignored) {}
        try { initMapper.migrateClassFileTableAddBatchId(); } catch (Exception ignored) {}
        logger.info("create database finish");
    }

    public static void saveJar(String jarPath) {
        JarEntity en = new JarEntity();
        en.setJarAbsPath(jarPath);
        if (OSUtil.isWindows()) {
            String[] temp = jarPath.split("\\\\");
            en.setJarName(temp[temp.length - 1]);
        } else {
            String[] temp = jarPath.split("/");
            en.setJarName(temp[temp.length - 1]);
        }
        List<JarEntity> js = new ArrayList<>();
        js.add(en);
        int i = jarMapper.insertJar(js);
        if (i != 0) {
            logger.debug("save jar finish");
        }
    }

    /**
     * D11：用显式 jarName 保存 jar（不从路径推导）。
     * 归一化摄入中，归档已被解压成目录，原归档文件不再存在；用解压目录路径作唯一 absPath（key），
     * 但 jarName 须保留原归档名（如 audit-commons-1.0.0.jar）以保证事实库 jar 归属显示正确。
     */
    public static void saveJarWithName(String jarAbsKey, String jarName) {
        JarEntity en = new JarEntity();
        en.setJarAbsPath(jarAbsKey);
        en.setJarName(jarName);
        List<JarEntity> js = new ArrayList<>();
        js.add(en);
        int i = jarMapper.insertJar(js);
        if (i != 0) {
            logger.debug("save jar (named) finish");
        }
    }

    public static void saveJarWithNameAndHash(String jarAbsKey, String jarName, String contentHash) {
        JarEntity en = new JarEntity();
        en.setJarAbsPath(jarAbsKey);
        en.setJarName(jarName);
        en.setContentHash(contentHash);
        List<JarEntity> js = new ArrayList<>();
        js.add(en);
        int i = jarMapper.insertJar(js);
        if (i != 0) {
            logger.debug("save jar (named+hash) finish");
        }
    }

    public static JarEntity getJarId(String jarPath) {
        List<JarEntity> jarEntities = jarMapper.selectJarByAbsPath(jarPath);
        if (jarEntities == null || jarEntities.isEmpty()) {
            return null;
        }
        Map<String, JarEntity> distinct = new LinkedHashMap<>();
        for (JarEntity jarEntity : jarEntities) {
            distinct.putIfAbsent(jarEntity.getJarName(), jarEntity);
        }
        return distinct.values().stream().findFirst().orElse(null);
    }

    public static void saveClassFiles(Set<ClassFileEntity> classFileList) {
        logger.info("total class file: {}", classFileList.size());
        List<ClassFileEntity> list = new ArrayList<>();
        for (ClassFileEntity classFile : classFileList) {
            classFile.setPathStr(classFile.getPath().toAbsolutePath().toString());
            if (classFile.getJarId() == null) {
                classFile.setJarId(-1);
            }
            list.add(classFile);
        }
        List<List<ClassFileEntity>> partition = PartitionUtils.partition(list, PART_SIZE);
        for (List<ClassFileEntity> data : partition) {
            int a = classFileMapper.insertClassFile(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save class file finish");
    }

    public static void saveClassInfo(Set<ClassReference> discoveredClasses) {
        logger.info("total class: {}", discoveredClasses.size());
        totalClassCount = discoveredClasses.size();
        List<ClassEntity> list = new ArrayList<>();
        for (ClassReference reference : discoveredClasses) {
            ClassEntity classEntity = new ClassEntity();
            classEntity.setJarName(reference.getJarName());
            classEntity.setJarId(reference.getJarId());
            classEntity.setVersion(reference.getVersion());
            classEntity.setAccess(reference.getAccess());
            classEntity.setClassName(reference.getName());
            classEntity.setSuperClassName(reference.getSuperClass());
            classEntity.setInterface(reference.isInterface());
            list.add(classEntity);
        }
        List<List<ClassEntity>> partition = PartitionUtils.partition(list, PART_SIZE);
        for (List<ClassEntity> data : partition) {
            int a = classMapper.insertClass(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save class finish");

        List<MemberEntity> mList = new ArrayList<>();
        List<AnnoEntity> aList = new ArrayList<>();
        List<InterfaceEntity> iList = new ArrayList<>();
        for (ClassReference reference : discoveredClasses) {
            for (ClassReference.Member member : reference.getMembers()) {
                MemberEntity memberEntity = new MemberEntity();
                memberEntity.setMemberName(member.getName());
                memberEntity.setModifiers(member.getModifiers());
                memberEntity.setValue(member.getValue());
                memberEntity.setTypeClassName(member.getType().getName());
                memberEntity.setClassName(reference.getName());
                memberEntity.setMethodDesc(member.getDesc());
                memberEntity.setMethodSignature(member.getSignature());
                memberEntity.setJarId(reference.getJarId());
                mList.add(memberEntity);
            }
            for (AnnoReference anno : reference.getAnnotations()) {
                AnnoEntity annoEntity = new AnnoEntity();
                annoEntity.setAnnoName(anno.getAnnoName());
                annoEntity.setVisible(anno.getVisible() ? 1 : 0);
                annoEntity.setClassName(reference.getName());
                annoEntity.setJarId(reference.getJarId());
                annoEntity.setParameter(anno.getParameter());
                aList.add(annoEntity);
            }
            for (String inter : reference.getInterfaces()) {
                InterfaceEntity interfaceEntity = new InterfaceEntity();
                interfaceEntity.setClassName(reference.getName());
                interfaceEntity.setInterfaceName(inter);
                interfaceEntity.setJarId(reference.getJarId());
                iList.add(interfaceEntity);
            }
        }
        List<List<MemberEntity>> mPartition = PartitionUtils.partition(mList, PART_SIZE);
        for (List<MemberEntity> data : mPartition) {
            int a = memberMapper.insertMember(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save member success");

        saveAnno(aList);
        logger.info("save class anno success");

        List<List<InterfaceEntity>> iPartition = PartitionUtils.partition(iList, PART_SIZE);
        for (List<InterfaceEntity> data : iPartition) {
            int a = interfaceMapper.insertInterface(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save interface success");
    }

    private static void saveAnno(List<AnnoEntity> aList) {
        List<List<AnnoEntity>> aPartition = PartitionUtils.partition(aList, PART_SIZE);
        for (List<AnnoEntity> data : aPartition) {
            int a = annoMapper.insertAnno(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
    }

    public static void saveMethods(Set<MethodReference> discoveredMethods) {
        logger.info("total method: {}", discoveredMethods.size());
        totalMethodCount = discoveredMethods.size();
        List<MethodEntity> mList = new ArrayList<>();
        List<AnnoEntity> aList = new ArrayList<>();
        for (MethodReference reference : discoveredMethods) {
            MethodEntity methodEntity = new MethodEntity();
            methodEntity.setMethodName(reference.getName());
            methodEntity.setMethodDesc(reference.getDesc());
            methodEntity.setClassName(reference.getClassReference().getName());
            methodEntity.setStatic(reference.isStatic());
            methodEntity.setAccess(reference.getAccess());
            methodEntity.setLineNumber(reference.getLineNumber());
            methodEntity.setJarId(reference.getJarId());
            mList.add(methodEntity);
            for (AnnoReference anno : reference.getAnnotations()) {
                AnnoEntity annoEntity = new AnnoEntity();
                annoEntity.setAnnoName(anno.getAnnoName());
                annoEntity.setMethodName(reference.getName());
                annoEntity.setClassName(reference.getClassReference().getName());
                annoEntity.setJarId(reference.getJarId());
                annoEntity.setVisible(anno.getVisible() ? 1 : 0);
                annoEntity.setParameter(anno.getParameter());
                aList.add(annoEntity);
            }
        }
        List<List<MethodEntity>> mPartition = PartitionUtils.partition(mList, PART_SIZE);
        for (List<MethodEntity> data : mPartition) {
            int a = methodMapper.insertMethod(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save method success");
        saveAnno(aList);
        logger.info("save method anno success");
    }

    public static void saveMethodCalls(HashMap<MethodReference.Handle,
            HashSet<MethodReference.Handle>> methodCalls) {
        List<MethodCallEntity> mList = new ArrayList<>();
        for (Map.Entry<MethodReference.Handle, HashSet<MethodReference.Handle>> call :
                methodCalls.entrySet()) {
            MethodReference.Handle caller = call.getKey();
            HashSet<MethodReference.Handle> callee = call.getValue();
            for (MethodReference.Handle mh : callee) {
                MethodCallEntity mce = new MethodCallEntity();
                mce.setCallerClassName(caller.getClassReference().getName());
                mce.setCallerMethodName(caller.getName());
                mce.setCallerMethodDesc(caller.getDesc());
                mce.setCallerJarId(AnalyzeEnv.classMap.getOrDefault(
                        caller.getClassReference(), notFoundClassReference).getJarId());
                mce.setCalleeClassName(mh.getClassReference().getName());
                mce.setCalleeMethodName(mh.getName());
                mce.setCalleeMethodDesc(mh.getDesc());
                mce.setCalleeJarId(AnalyzeEnv.classMap.getOrDefault(
                        mh.getClassReference(), notFoundClassReference).getJarId());
                mce.setOpCode(mh.getOpcode());
                mList.add(mce);
            }
        }
        List<List<MethodCallEntity>> mPartition = PartitionUtils.partition(mList, PART_SIZE);
        for (List<MethodCallEntity> data : mPartition) {
            int a = methodCallMapper.insertMethodCall(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save method call success");
    }

    public static void saveImpls(Map<MethodReference.Handle, Set<MethodReference.Handle>> implMap) {
        List<MethodImplEntity> mList = new ArrayList<>();
        for (Map.Entry<MethodReference.Handle, Set<MethodReference.Handle>> call :
                implMap.entrySet()) {
            MethodReference.Handle method = call.getKey();
            Set<MethodReference.Handle> impls = call.getValue();
            for (MethodReference.Handle mh : impls) {
                MethodImplEntity impl = new MethodImplEntity();
                impl.setImplClassName(mh.getClassReference().getName());
                impl.setClassName(method.getClassReference().getName());
                impl.setMethodName(mh.getName());
                impl.setMethodDesc(mh.getDesc());
                impl.setClassJarId(AnalyzeEnv.classMap.getOrDefault(
                        method.getClassReference(), notFoundClassReference).getJarId());
                impl.setImplClassJarId(AnalyzeEnv.classMap.getOrDefault(
                        mh.getClassReference(), notFoundClassReference).getJarId());
                mList.add(impl);
            }
        }
        List<List<MethodImplEntity>> mPartition = PartitionUtils.partition(mList, PART_SIZE);
        for (List<MethodImplEntity> data : mPartition) {
            int a = methodImplMapper.insertMethodImpl(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save method impl success");
    }

    public static void saveStrMap(Map<MethodReference.Handle, List<String>> strMap,
                                  Map<MethodReference.Handle, List<String>> stringAnnoMap) {
        List<StringEntity> mList = new ArrayList<>();
        logger.info("save str map length: {}", strMap.size());
        for (Map.Entry<MethodReference.Handle, List<String>> strEntry : strMap.entrySet()) {
            MethodReference.Handle method = strEntry.getKey();
            List<String> strList = strEntry.getValue();
            for (String s : strList) {
                MethodReference mr = AnalyzeEnv.methodMap.get(method);
                if (mr == null) {
                    logger.warn("method not found in methodMap: {}", method);
                    continue;
                }
                ClassReference cr = AnalyzeEnv.classMap.get(mr.getClassReference());
                if (cr == null) {
                    logger.warn("class not found in classMap: {}", mr.getClassReference());
                    continue;
                }
                StringEntity stringEntity = new StringEntity();
                stringEntity.setValue(s);
                stringEntity.setAccess(mr.getAccess());
                stringEntity.setClassName(cr.getName());
                stringEntity.setJarName(cr.getJarName());
                stringEntity.setJarId(cr.getJarId());
                stringEntity.setMethodDesc(mr.getDesc());
                stringEntity.setMethodName(mr.getName());
                mList.add(stringEntity);
            }
        }
        logger.info("save string anno map length: {}", stringAnnoMap.size());
        for (Map.Entry<MethodReference.Handle, List<String>> strEntry : stringAnnoMap.entrySet()) {
            MethodReference.Handle method = strEntry.getKey();
            List<String> strList = strEntry.getValue();
            for (String s : strList) {
                MethodReference mr = AnalyzeEnv.methodMap.get(method);
                if (mr == null) {
                    logger.warn("method not found in methodMap for anno: {}", method);
                    continue;
                }
                ClassReference cr = AnalyzeEnv.classMap.get(mr.getClassReference());
                if (cr == null) {
                    logger.warn("class not found in classMap for anno: {}", mr.getClassReference());
                    continue;
                }
                StringEntity stringEntity = new StringEntity();
                stringEntity.setValue(s);
                stringEntity.setAccess(mr.getAccess());
                stringEntity.setClassName(cr.getName());
                stringEntity.setJarName(cr.getJarName());
                stringEntity.setJarId(cr.getJarId());
                stringEntity.setMethodDesc(mr.getDesc());
                stringEntity.setMethodName(mr.getName());
                mList.add(stringEntity);
            }
        }
        List<List<StringEntity>> mPartition = PartitionUtils.partition(mList, PART_SIZE);
        for (List<StringEntity> data : mPartition) {
            int a = stringMapper.insertString(data);
            if (a == 0) {
                logger.warn("save error");
            }
        }
        logger.info("save all string success");
    }
}
