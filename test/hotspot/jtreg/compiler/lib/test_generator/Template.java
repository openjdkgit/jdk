/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package compiler.lib.test_generator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class Template {
    public Template() {}
    static int variableNumber = 1;
    /*
     * This method processes a given template string to avoid variable name conflicts by appending a unique identifier.
     * It searches for placeholders within the string, identified by a '$' followed by a word (variable name),
     * and replaces each placeholder with the variable name concatenated with a unique number.
     */
    public static String avoidConflict(String temp){
        StringBuilder result = new StringBuilder();
        String regex="\\$(\\w+)";
        Pattern pat = Pattern.compile(regex);
        Matcher mat = pat.matcher(temp);
        while(mat.find()){
            String replacement = mat.group(1)+variableNumber;
            mat.appendReplacement(result, replacement);
        }
        mat.appendTail(result);
        variableNumber++;
        return result.toString();
    }

    public abstract String getTemplate(String variable);
}
