/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.reachability.summaries;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.flow.AnalysisParsedGraph;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.phases.InlineBeforeAnalysis;
import com.oracle.graal.reachability.MethodSummary;
import com.oracle.graal.reachability.MethodSummaryProvider;
import com.oracle.graal.reachability.SerializableMethodSummary;
import com.oracle.graal.reachability.SimpleInMemoryMethodSummaryProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.options.OptionKey;
import org.graalvm.compiler.options.OptionType;
import org.graalvm.compiler.options.OptionValues;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class MethodSummaryStorage implements MethodSummaryProvider {

    public static class Options {
        @Option(help = "Summary storage file", type = OptionType.User)//
        public static final OptionKey<String> SummaryFile = new OptionKey<>("");

        @Option(help = "Summary storage file", type = OptionType.User)//
        public static final OptionKey<String> SummaryFilter = new OptionKey<>("Main\\.main");
    }

    private final String summaryFilter;

    private final Map<AnalysisMethod, PersistedSummary> storage = new ConcurrentHashMap<>();

    private final HashingStrategy hashingStrategy = new DummyHashingStrategy();

    private final ResolutionStrategy resolutionStrategy;
    private final SimpleInMemoryMethodSummaryProvider simpleInMemoryMethodSummaryProvider;
    private final OptionValues options;

    public MethodSummaryStorage(ResolutionStrategy resolutionStrategy, SimpleInMemoryMethodSummaryProvider simpleInMemoryMethodSummaryProvider, OptionValues options) {
        this.resolutionStrategy = resolutionStrategy;
        this.simpleInMemoryMethodSummaryProvider = simpleInMemoryMethodSummaryProvider;
        this.options = options;
        this.summaryFilter = Options.SummaryFilter.getValue(options);
        System.out.println("Using summary filter " + summaryFilter);
    }

    @Override
    public MethodSummary getSummary(BigBang bb, AnalysisMethod method) {
        PersistedSummary persistedSummary = storage.get(method);
        if (persistedSummary != null) {
// System.out.println("Reusing summary for " + method);
            bb.postTask(d -> {
// System.out.println("Async parsing of " + method);
                AnalysisParsedGraph analysisParsedGraph = method.ensureGraphParsed(bb);
                StructuredGraph decoded = InlineBeforeAnalysis.decodeGraph(bb, method, analysisParsedGraph);
                method.setAnalyzedGraph(decoded);
            });
            return persistedSummary.getSummary();
        }
        MethodSummary summary = simpleInMemoryMethodSummaryProvider.getSummary(bb, method);
        PersistedSummary preparedSummary = hashingStrategy.prepare(method, summary);
        storage.put(method, preparedSummary);
        return summary;
    }

    @Override
    public MethodSummary getSummary(BigBang bigBang, StructuredGraph graph) {
        return simpleInMemoryMethodSummaryProvider.getSummary(bigBang, graph);
    }

    public void loadData() {
        String summaryFileName = Options.SummaryFile.getValue(options);
        if (summaryFileName.isEmpty()) {
            return;
        }
        try (ObjectInputStream stream = new ObjectInputStream(new FileInputStream(summaryFileName))) {
            @SuppressWarnings("unchecked")
            Map<SerializableMethodSummary.MethodId, SerializableMethodSummary> summaries = (Map<SerializableMethodSummary.MethodId, SerializableMethodSummary>) stream.readObject();
            processSummaryFile(summaries);
        } catch (NullPointerException | IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void processSummaryFile(Map<SerializableMethodSummary.MethodId, SerializableMethodSummary> summaries) {
        int success = 0;
        int all = 0;
        for (Map.Entry<SerializableMethodSummary.MethodId, SerializableMethodSummary> methodEntry : summaries.entrySet()) {
// System.out.println("!! " + methodEntry.getKey());
            all++;
            AnalysisMethod analysisMethod = resolutionStrategy.resolveMethod(methodEntry.getKey());
            if (analysisMethod == null) {
// err("Could not resolve method " + methodEntry.getKey());
                continue;
            }
            try {
                if (hashingStrategy.isValid(analysisMethod, methodEntry.getValue())) {
                    MethodSummary resolvedSummary = resolveSummary(methodEntry.getValue());
// System.out.println(resolvedSummary);
                    storage.put(analysisMethod, hashingStrategy.prepare(analysisMethod, resolvedSummary));
                    success++;
                } else {
// err("Method summary for " + analysisMethod + " is not valid.");
                }
            } catch (RuntimeException ex) {
// err("Cannot resolve summary for " + analysisMethod + ": " + ex.getMessage());
                continue;
            }
        }
        System.out.println("!!! Loaded " + success + " out of " + all + " summaries.");
    }

    private MethodSummary resolveSummary(SerializableMethodSummary value) {
        // todo exclude class with unstable names
        AnalysisMethod[] invokedMethods = resolveHelper(value.invokedMethods, resolutionStrategy::resolveMethod, AnalysisMethod.class);
        AnalysisMethod[] implementationInvokedMethods = resolveHelper(value.implementationInvokedMethods, resolutionStrategy::resolveMethod, AnalysisMethod.class);
        AnalysisType[] accessedTypes = resolveHelper(value.accessedTypes, resolutionStrategy::resolveClass, AnalysisType.class);
        AnalysisType[] instantiatedTypes = resolveHelper(value.instantiatedTypes, resolutionStrategy::resolveClass, AnalysisType.class);
        AnalysisField[] readFields = resolveHelper(value.readFields, resolutionStrategy::resolveField, AnalysisField.class);
        AnalysisField[] writtenFields = resolveHelper(value.writtenFields, resolutionStrategy::resolveField, AnalysisField.class);
        return new MethodSummary(invokedMethods, implementationInvokedMethods, accessedTypes, instantiatedTypes, readFields, writtenFields);
    }

    public <SerializedType, Type> Type[] resolveHelper(SerializedType[] inputArray, Function<SerializedType, Type> resolver, Class<Type> typeTag) {
        @SuppressWarnings("unchecked")
        Type[] res = (Type[]) Array.newInstance(typeTag, inputArray.length);
        for (int i = 0; i < inputArray.length; i++) {
            SerializedType elem = inputArray[i];
            Type resolved = resolver.apply(elem);
            if (resolved == null) {
                throw new RuntimeException("Failed to resolve " + elem);
            }
            res[i] = resolved;
        }
        return res;
    }

    public void persistData(Collection<ResolvedJavaMethod> summariesToSkip) {
        String summaryFileName = Options.SummaryFile.getValue(options);
        if (summaryFileName.isEmpty()) {
            return;
        }
        System.out.println("Skipping summaries for " + summariesToSkip.size() + " methods");
        try (ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(summaryFileName))) {
            stream.writeObject(serializeStorage(summariesToSkip));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<SerializableMethodSummary.MethodId, SerializableMethodSummary> serializeStorage(Collection<ResolvedJavaMethod> summariesToSkip) {
        Map<SerializableMethodSummary.MethodId, SerializableMethodSummary> serializableMap = new HashMap<>();
        for (Map.Entry<AnalysisMethod, PersistedSummary> entry : storage.entrySet()) {
            if (!canSerialize(entry.getKey(), entry.getValue(), summariesToSkip)) {
                continue;
            }
// System.out.println("Storing summary for " + entry.getKey());
            SerializableMethodSummary summary = serializeSummary(entry.getValue());
            if (summary != null) {
                serializableMap.put(resolutionStrategy.getId(entry.getKey()), summary);
            }
        }
        System.out.println("Saving " + serializableMap.size() + " summaries out of " + storage.size());
        return serializableMap;
    }

    private SerializableMethodSummary serializeSummary(PersistedSummary persistedSummary) {
        MethodSummary summary = persistedSummary.getSummary();

        SerializableMethodSummary.MethodId[] invokedMethods = Arrays.stream(summary.invokedMethods).map(resolutionStrategy::getId).toArray(SerializableMethodSummary.MethodId[]::new);
        if (Arrays.stream(invokedMethods).map(it -> it.classId).anyMatch(this::isInvalid)) {
            return null;
        }
        SerializableMethodSummary.MethodId[] implInvokedMethods = Arrays.stream(summary.implementationInvokedMethods).map(resolutionStrategy::getId).toArray(SerializableMethodSummary.MethodId[]::new);
        if (Arrays.stream(implInvokedMethods).map(it -> it.classId).anyMatch(this::isInvalid)) {
            return null;
        }
        SerializableMethodSummary.ClassId[] accessedTypes = Arrays.stream(summary.accessedTypes).map(resolutionStrategy::getId).toArray(SerializableMethodSummary.ClassId[]::new);
        if (Arrays.stream(accessedTypes).anyMatch(this::isInvalid)) {
            return null;
        }
        SerializableMethodSummary.ClassId[] instantiatedTypes = Arrays.stream(summary.instantiatedTypes).map(resolutionStrategy::getId).toArray(SerializableMethodSummary.ClassId[]::new);
        if (Arrays.stream(instantiatedTypes).anyMatch(this::isInvalid)) {
            return null;
        }
        SerializableMethodSummary.FieldId[] readFields = Arrays.stream(summary.readFields).map(resolutionStrategy::getId).toArray(SerializableMethodSummary.FieldId[]::new);
        if (Arrays.stream(readFields).map(it -> it.classId).anyMatch(this::isInvalid)) {
            return null;
        }
        SerializableMethodSummary.FieldId[] writtenFields = Arrays.stream(summary.writtenFields).map(resolutionStrategy::getId).toArray(SerializableMethodSummary.FieldId[]::new);
        if (Arrays.stream(writtenFields).map(it -> it.classId).anyMatch(this::isInvalid)) {
            return null;
        }
        // todo
//        summary.foreignCallDescriptors;
//        summary.foreignCallSignatures;
//        summary.embeddedConstants;
        return new SerializableMethodSummary(persistedSummary.getHash(), invokedMethods, implInvokedMethods, accessedTypes, instantiatedTypes, readFields, writtenFields);
    }

    private boolean isInvalid(SerializableMethodSummary.ClassId classId) {
        return classId.className.contains("$Lambda$") || classId.className.contains("[]") || classId.className.contains("$$ProxyImpl");
    }

    private boolean canSerialize(AnalysisMethod method, PersistedSummary persistedSummary, Collection<ResolvedJavaMethod> summariesToSkip) {
        if (summariesToSkip.contains(method)) {
//            System.out.println("skipping " + method + " as it was marked as not valid");
            return false;
        }
        return method.getQualifiedName().matches(summaryFilter);
    }

    public void err(String msg) {
        System.err.println(msg);
    }
}
