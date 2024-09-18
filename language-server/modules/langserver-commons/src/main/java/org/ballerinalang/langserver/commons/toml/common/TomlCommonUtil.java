/*
 * Copyright (c) 2021, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ballerinalang.langserver.commons.toml.common;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;

/**
 * Contains Utilities related toml language support features.
 *
 * @since 2.0.0
 */
public final class TomlCommonUtil {

    public static final String LINE_SEPARATOR = System.lineSeparator();

    private TomlCommonUtil() {

    }

    /**
     * Generate the sort text when given the rank.
     * Sort Text is generated by providing an All Caps String which includes only the english alphabetical
     * characters within 65-90 ASCII range.
     *
     * @param rank rank to be assigned. Rank should be a non zero integer
     * @return {@link String} generated sort text
     */
    public static String genSortText(int rank) {
        int rankRange = 25;
        int rankUpperBoundary = 64;
        int rankLowerBoundary = 90;
        if (rank < 1) {
            throw new IllegalArgumentException("Rank should be greater than zero");
        }

        int suffixValue = rank % rankRange;
        String suffix = suffixValue == 0 ? "" : String.valueOf((char) (rankUpperBoundary + suffixValue));
        String prefix = String.join("", Collections.nCopies((rank - suffixValue) / rankRange,
                (char) rankLowerBoundary + ""));

        return prefix + suffix;
    }

    /**
     * Get the path from given string URI.
     *
     * @param uri file uri
     * @return {@link Optional} Path from the URI
     */
    public static Optional<Path> getPathFromURI(String uri) {
        try {
            return Optional.of(Paths.get(new URL(uri).toURI()));
        } catch (URISyntaxException | MalformedURLException e) {
            // ignore
        }
        return Optional.empty();
    }
}

