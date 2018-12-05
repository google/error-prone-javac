/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.tools.jdeps;

import java.io.IOException;
import java.util.*;

/**
 * Build the profile information from ct.sym if exists.
 */
enum Profile {
    COMPACT1("compact1", 1, "java.compact1"),
    COMPACT2("compact2", 2, "java.compact2"),
    COMPACT3("compact3", 3, "java.compact3"),
    FULL_JRE("Full JRE", 4, "java.se");

    final String name;
    final int profile;
    final String moduleName;
    final Set<Module> modules = new HashSet<>();

    Profile(String name, int profile, String moduleName) {
        this.name = name;
        this.profile = profile;
        this.moduleName = moduleName;
    }

    public String profileName() {
        return name;
    }

    @Override
    public String toString() {
        return moduleName;
    }

    public static int getProfileCount() {
        return JDK.isEmpty() ? 0 : Profile.values().length;
    }

    /**
     * Returns the Profile for the given package name; null if not found.
     */
    public static Profile getProfile(String pn) {
        for (Profile p : Profile.values()) {
            for (Module m : p.modules) {
                if (m.packages().contains(pn)) {
                    return p;
                }
            }
        }
        return null;
    }

    /*
     * Returns the Profile for a given Module; null if not found.
     */
    public static Profile getProfile(Module m) {
        for (Profile p : Profile.values()) {
            if (p.modules.contains(m)) {
                return p;
            }
        }
        return null;
    }

    final static Set<Module> JDK = new HashSet<>();
    static void initProfiles() {
        for (Profile p : Profile.values()) {
            Module m = PlatformClassPath.findModule(p.moduleName);
            if (m == null)
                throw new Error(p.moduleName + " doesn't exist");
            p.modules.add(m);
            JDK.add(m);
            for (String n : m.requires().keySet()) {
                Module d = PlatformClassPath.findModule(n);
                if (d == null)
                    throw new Error(n + " doesn't exist");
                p.modules.add(d);
                JDK.add(d);
            }
        }
    }
    // for debugging
    public static void main(String[] args) throws IOException {
        // find platform modules
        PlatformClassPath.getArchives(null);
        if (Profile.getProfileCount() == 0) {
            System.err.println("No profile is present in this JDK");
        }
        for (Profile p : Profile.values()) {
            String profileName = p.name;
            System.out.format("%2d: %-10s  %s%n", p.profile, profileName, p.modules);
            for (Module m: p.modules) {
                System.out.format("module %s%n", m.name());
                System.out.format("   requires %s%n", m.requires());
                for (Map.Entry<String,Set<String>> e: m.exports().entrySet()) {
                    System.out.format("   exports %s %s%n", e.getKey(),
                        e.getValue().isEmpty() ? "" : "to " + e.getValue());
                }
            }
        }
        System.out.println("All JDK modules:-");
        JDK.stream().sorted(Comparator.comparing(Module::name))
           .forEach(m -> System.out.println(m));
    }
}
