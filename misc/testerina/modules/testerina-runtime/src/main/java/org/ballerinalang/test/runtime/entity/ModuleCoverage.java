/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ballerinalang.test.runtime.entity;

import org.ballerinalang.test.runtime.util.TesterinaConstants;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coverage analysis of a specific module used to generate the Json object.
 */
public class ModuleCoverage {

    private int coveredLines;
    private int missedLines;
    private float coveragePercentage;
    private Map<String, SourceFile> sourceFiles = new HashMap<>();

    private static PrintStream errStream = System.err;

    private static ModuleCoverage instance = new ModuleCoverage();
    public static ModuleCoverage getInstance() {
        return instance;
    }

    public void addSourceFileCoverage (String moduleName, String fileName, List<Integer> coveredLines,
                                       List<Integer> missedLines) {
        SourceFile sourceFile = new SourceFile(moduleName, fileName, coveredLines, missedLines);
        this.sourceFiles.put(fileName, sourceFile);
        this.coveredLines += coveredLines.size();
        this.missedLines += missedLines.size();
        setCoveragePercentage();
    }

    private void setCoveragePercentage() {
        this.coveragePercentage = (float) this.coveredLines / (this.coveredLines + this.missedLines) * 100;
    }

    public float getCoveragePercentage() {
        return coveragePercentage;
    }

    private static class SourceFile {
        private List<Integer> coveredLines;
        private List<Integer> missedLines;
        private float coveragePercentage;
        private String sourceCode;

        private SourceFile(String moduleName, String fileName, List<Integer> coveredLines, List<Integer> missedLines) {
            this.coveredLines = coveredLines;
            this.missedLines = missedLines;
            setCoveragePercentage(coveredLines, missedLines);
            setSourceCode(moduleName, fileName);
        }

        private void setCoveragePercentage(List<Integer> coveredLines, List<Integer> missedLines) {
            this.coveragePercentage = (float) coveredLines.size() / (coveredLines.size() + missedLines.size()) * 100;
        }

        private void setSourceCode(String moduleName, String fileName) {
            Path sourceFile = Paths.get(TesterinaConstants.SRC_DIR).resolve(moduleName).resolve(fileName);
            StringBuilder stringBuilder = new StringBuilder("<ol>");

            try {
                final List<String> allLines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
                int i = 1;
                String color = "";
                for (String line: allLines) {
                    if (this.coveredLines.contains(i)) {
                        color = TesterinaConstants.GREEN;
                    } else if (this.missedLines.contains(i)) {
                        color = TesterinaConstants.RED;
                    }
                    stringBuilder.append("<li class \"").append(color).append("\">").append(line).append("</li>\n");
                    i++;
                }
                stringBuilder.append("</ol>");
            } catch (IOException e) {
                errStream.println("error while analyzing code coverage" + e.getMessage());
                Runtime.getRuntime().exit(1);
            }

            /*try (InputStream inputStream = new FileInputStream(sourceFile.toString())) {
                bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                String line = bufferedReader.readLine();
                int i = 1;
                String color = "";

                while (line != null) {
                    if (this.coveredLines.contains(i)) {
                        color = TesterinaConstants.GREEN;
                    } else if (this.missedLines.contains(i)) {
                        color = TesterinaConstants.RED;
                    }
                    stringBuilder.append("<li class \"").append(color).append("\">").append(line).append("</li>\n");
                    line = bufferedReader.readLine();
                    i++;
                }
                stringBuilder.append("</ol>");
            } catch (IOException e) {
                errStream.println("error while analyzing code coverage" + e.getMessage());
                Runtime.getRuntime().exit(1);
            }*/

//            try (InputStream is = new FileInputStream(sourceFile.toString())) {
//                bufferedReader = new BufferedReader(new InputStreamReader(is));
//                String line = bufferedReader.readLine();
//                int i = 1;
//                String color = "";
//
//                while (line != null) {
//                    if (this.coveredLines.contains(i)) {
//                        color = TesterinaConstants.GREEN;
//                    } else if (this.missedLines.contains(i)) {
//                        color = TesterinaConstants.RED;
//                    }
//                    stringBuilder.append("<li class \"").append(color).append("\">").append(line).append("</li>\n");
//                    line = bufferedReader.readLine();
//                    i++;
//                }
//                stringBuilder.append("</ol>");
//            } catch (IOException e) {
//                errStream.println("error while analyzing code coverage" + e.getMessage());
//                Runtime.getRuntime().exit(1);
//            }
            this.sourceCode = stringBuilder.toString();
        }

        public float getCoveragePercentage() {
            return coveragePercentage;
        }

        public String getSourceCode() {
            return sourceCode;
        }
    }
}
