/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.tooling.jvm

/**
 * Verifies JDK compatibility for tooling api when setting the java home via Gradle property.
 */
class GradlePropertyBuildJvmCrossVersionSpec extends ExplicitDaemonJvmCrossVersionSpec {

    @Override
    void configureBuild(String majorVersion, File javaHome) {
        propertiesFile.writeProperties("org.gradle.java.home": javaHome.canonicalPath)
    }

}
