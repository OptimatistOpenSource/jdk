/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.launcher;

import com.sun.tools.javac.api.JavacTool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Describes a launch-able Java compilation unit.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b></p>
 */
public record ProgramDescriptor(Path sourceFilePath, Optional<String> packageName, Path sourceRootPath) {
    static ProgramDescriptor of(Path file) {
        try {
            var compiler = JavacTool.create();
            var standardFileManager = compiler.getStandardFileManager(null, null, null);
            var units = List.of(standardFileManager.getJavaFileObject(file));
            var task = compiler.getTask(null, standardFileManager, diagnostic -> {}, null, null, units);
            for (var tree : task.parse()) {
                var packageTree = tree.getPackage();
                if (packageTree != null) {
                    var packageName = packageTree.getPackageName().toString();
                    var root = computeSourceRootPath(file, packageName);
                    return new ProgramDescriptor(file, Optional.of(packageName), root);
                }
            }
        } catch (Exception ignore) {
            // fall through
        }
        var root = computeSourceRootPath(file, "");
        return new ProgramDescriptor(file, Optional.empty(), root);
    }

    public static Path computeSourceRootPath(Path program, String packageName) {
        var absolute = program.normalize().toAbsolutePath();
        var absoluteRoot = absolute.getRoot();
        if (packageName.isEmpty()) {
            var parent = absolute.getParent();
            if (parent == null) return absoluteRoot;
            return parent;
        }
        var packagePath = Path.of(packageName.replace('.', '/'));
        var ending = packagePath.resolve(program.getFileName());
        if (absolute.endsWith(ending)) {
            var max = absolute.getNameCount() - ending.getNameCount();
            if (max == 0) return absoluteRoot;
            return absoluteRoot.resolve(absolute.subpath(0, max));
        }
        throw new RuntimeException("Package " + packageName + " does match path ending: " + ending);
    }

    public boolean isModular() {
        return Files.exists(sourceRootPath.resolve("module-info.java"));
    }

    public Set<String> computePackageNames() {
        try (var stream = Files.find(sourceRootPath, 99, (path, attr) -> attr.isDirectory())) {
            var names = new TreeSet<String>();
            stream.filter(ProgramDescriptor::containsAtLeastOneRegularFile)
                  .map(sourceRootPath::relativize)
                  .map(Path::toString)
                  .filter(string -> !string.isEmpty())
                  .map(string -> string.replace('/', '.'))
                  .map(string -> string.replace('\\', '.'))
                  .forEach(names::add);
            return names;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static boolean containsAtLeastOneRegularFile(Path directory) {
        try (var stream = Files.newDirectoryStream(directory, Files::isRegularFile)) {
            return stream.iterator().hasNext();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
