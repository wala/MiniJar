/*
 * Copyright (c) 2021 IBM Corporation.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial implementation
 */
package minijar;

import java.util.Set;
import java.util.HashSet;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.cha.IClassHierarchy;

public class MethodUtil {
    public static Set<IClass> getSuperClasses(IMethod m, IClassHierarchy cha) {
        Set<IClass> superClasses = new HashSet<IClass>();
        IClass superClass = m.getDeclaringClass().getSuperclass();

        while(superClass != null) {
            superClasses.add(superClass);
            superClass = superClass.getSuperclass();
        }

        return superClasses;
    }

    public static Set<String> getSuperMethods(IMethod m, IClassHierarchy cha) {
        Set<String> ret = new HashSet<String>();

        MethodDescriptor desc = new MethodDescriptor(m);
    
        for(IClass klass: getSuperClasses(m, cha)) {
          String superMethod = getMethodDesc(klass, desc.getNameType());
          if (superMethod != null) {
            ret.add(superMethod);
          }
        }
    
        for(IClass klass: m.getDeclaringClass().getAllImplementedInterfaces()){
          String superMethod = getMethodDesc(klass, desc.getNameType());
          if (superMethod != null) {
            ret.add(superMethod);
          }
        }
    
        return ret;
      }

    public static String getMethodDesc(IClass klass, String methodName) {
        IMethod m  = getMethod(klass, methodName);
        if (m != null) {
            MethodDescriptor desc = new MethodDescriptor(m);
            return desc.toString();
        } else {
            return null;
        }
    }

    public static IMethod getMethod(IClass klass, String methodName) {
        for (IMethod m: klass.getAllMethods()) {
            MethodDescriptor desc = new MethodDescriptor(m);
          String superMethodName = desc.getNameType();
          if (superMethodName.equals(methodName)) {
            return m;
          }
        }
        return null;
      }
}
