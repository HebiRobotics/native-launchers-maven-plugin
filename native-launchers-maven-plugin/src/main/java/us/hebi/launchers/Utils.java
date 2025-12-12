/*-
 * #%L
 * native-launchers-maven-plugin Maven Mojo
 * %%
 * Copyright (C) 2023 HEBI Robotics
 * %%
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
 * #L%
 */

package us.hebi.launchers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * @author Florian Enner
 * @since 09 Jun 2023
 */
class Utils {

    public static boolean isNullOrEmpty(String str) {
        return str == null || str.isEmpty();
    }

    public static Path writeToDisk(String content, Path targetDir, String fileName) throws IOException {
        return Files.write(targetDir.resolve(fileName),
                content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    public static String loadResourceAsString(Class<?> clazz, String name) throws IOException {
        // There is no simple way in Java 8, so https://stackoverflow.com/a/46613809/3574093
        try (InputStream is = clazz.getResourceAsStream(name)) {
            if (is == null) throw new IllegalStateException("Resource not found: " + name);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }

    public static StringBuilder appendSpaced(StringBuilder builder, String value, int minLength) {
        builder.append(value);
        for (int i = value.length(); i < minLength; i++) {
            builder.append(' ');
        }
        return builder;
    }

    @SafeVarargs
    public static <T> T getNonNull(T... choices) {
        for (T choice : choices) {
            if (choice != null) return choice;
        }
        throw new IllegalArgumentException("all options are null");
    }

    public static boolean isMac() {
        return OS.contains("mac");
    }

    public static boolean isUnix() {
        return (OS.contains("nix") || OS.contains("nux") || OS.indexOf("aix") > 0);
    }

    public static boolean isWindows() {
        return OS.startsWith("win");
    }

    static final String OS = System.getProperty("os.name").toLowerCase(Locale.US);

}
