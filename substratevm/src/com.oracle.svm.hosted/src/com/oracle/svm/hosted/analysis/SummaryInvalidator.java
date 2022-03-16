package com.oracle.svm.hosted.analysis;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.Set;

public interface SummaryInvalidator {
    Set<ResolvedJavaMethod> summariesToSkip();
}
