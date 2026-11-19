/*
 * Copyright (c) 2026 Workato Inc. All rights reserved.
 */
package com.workato.agent.extension.test;

import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Resolves a serializable method reference to a {@link Resolved} record carrying the HTTP method
 * and the fully-interpolated URL path the controller handler responds to. Used by the harness's
 * {@code request(...)} / {@code urlFor(...)} overloads so a test does not have to repeat the
 * route string already declared on {@code @GetMapping} / {@code @PostMapping} / …
 *
 * <p>Mechanics: the SAM target of a method reference whose interface extends {@link Serializable}
 * generates a synthetic {@code writeReplace()} that returns a {@link SerializedLambda}. From that
 * we read the implementation class and method name, resolve the {@link Method} via reflection,
 * read its Spring web annotations, and substitute every {@code {name}} in the URL template with
 * the matching positional {@code pathVars} entry (positional across declared {@link PathVariable}
 * parameters, not across all parameters — body / query / header params are skipped).
 */
final class ControllerMethodResolver {

    private ControllerMethodResolver() {
    }

    static Resolved resolve(Serializable methodRef, Object[] pathVars) {
        SerializedLambda sl = serializedLambda(methodRef);
        Class<?> controllerClass = loadClass(sl.getImplClass().replace('/', '.'));
        Method method = findMethod(controllerClass, sl.getImplMethodName());

        String combinedPath = combine(classPath(controllerClass), methodPath(method));
        HttpMethod httpMethod = httpMethod(method);
        String interpolated = interpolatePathVariables(combinedPath, method, pathVars);
        return new Resolved(httpMethod, interpolated, controllerClass);
    }

    private static SerializedLambda serializedLambda(Serializable methodRef) {
        try {
            Method writeReplace = methodRef.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replaced = writeReplace.invoke(methodRef);
            if (!(replaced instanceof SerializedLambda sl)) {
                throw new IllegalArgumentException("Argument is not a serializable method reference: " + methodRef);
            }
            return sl;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Argument is not a serializable method reference — the functional interface must extend Serializable", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to decode method reference", e);
        }
    }

    private static Class<?> loadClass(String name) {
        // Use the resolver's own classloader (not TCCL) so the annotation classes it compares
        // against via `instanceof` match the ones materialised on the loaded Method. Under
        // DeployedAgentLauncher, TCCL is swapped to the agent's isolated classloader where Spring's
        // annotation classes are a different Class<?> instance from this resolver's own —
        // which breaks every `instanceof GetMapping` check below.
        ClassLoader cl = ControllerMethodResolver.class.getClassLoader();
        try {
            return Class.forName(name, true, cl);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Controller class not found: " + name, e);
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        Method match = null;
        for (Method m : type.getDeclaredMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException("Overloaded method " + name + " on " + type.getName()
                        + " — the method-reference form does not disambiguate overloads; use urlFor(String) instead");
            }
            match = m;
        }
        if (match == null) {
            throw new IllegalStateException("Method " + name + " not found on " + type.getName());
        }
        return match;
    }

    private static String classPath(Class<?> controllerClass) {
        RequestMapping rm = controllerClass.getAnnotation(RequestMapping.class);
        if (rm == null) {
            return "";
        }
        String[] paths = rm.value().length > 0 ? rm.value() : rm.path();
        return paths.length > 0 ? paths[0] : "";
    }

    private static String methodPath(Method method) {
        for (Annotation a : method.getAnnotations()) {
            String[] p = pathOf(a);
            if (p != null) {
                return p.length > 0 ? p[0] : "";
            }
        }
        throw new IllegalStateException("No @GetMapping / @PostMapping / @RequestMapping (etc.) on " + method);
    }

    private static String[] pathOf(Annotation a) {
        if (a instanceof GetMapping g) {
            return g.value().length > 0 ? g.value() : g.path();
        }
        if (a instanceof PostMapping p) {
            return p.value().length > 0 ? p.value() : p.path();
        }
        if (a instanceof PutMapping p) {
            return p.value().length > 0 ? p.value() : p.path();
        }
        if (a instanceof DeleteMapping d) {
            return d.value().length > 0 ? d.value() : d.path();
        }
        if (a instanceof PatchMapping p) {
            return p.value().length > 0 ? p.value() : p.path();
        }
        if (a instanceof RequestMapping r) {
            return r.value().length > 0 ? r.value() : r.path();
        }
        return null;
    }

    private static HttpMethod httpMethod(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return HttpMethod.GET;
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            return HttpMethod.POST;
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return HttpMethod.PUT;
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return HttpMethod.DELETE;
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return HttpMethod.PATCH;
        }
        RequestMapping rm = method.getAnnotation(RequestMapping.class);
        if (rm != null && rm.method().length > 0) {
            return HttpMethod.valueOf(rm.method()[0].name());
        }
        throw new IllegalStateException("No HTTP method inferable from annotations on " + method
                + " — use @GetMapping / @PostMapping / … or RequestMapping(method = ...)");
    }

    private static String combine(String classPath, String methodPath) {
        String a = classPath == null ? "" : classPath;
        String b = methodPath == null ? "" : methodPath;
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        boolean aEndsSlash = a.endsWith("/");
        boolean bStartsSlash = b.startsWith("/");
        if (aEndsSlash && bStartsSlash) {
            return a + b.substring(1);
        }
        if (!aEndsSlash && !bStartsSlash) {
            return a + "/" + b;
        }
        return a + b;
    }

    private static String interpolatePathVariables(String template, Method method, Object[] pathVars) {
        List<Parameter> pvParams = pathVariableParams(method);
        if (pvParams.size() != pathVars.length) {
            throw new IllegalArgumentException("Method " + method.getName() + " declares "
                    + pvParams.size() + " @PathVariable parameter(s), but " + pathVars.length + " value(s) were supplied");
        }
        String result = template;
        for (int i = 0; i < pvParams.size(); i++) {
            Parameter p = pvParams.get(i);
            String varName = resolvePathVariableName(p);
            String encoded = URLEncoder.encode(String.valueOf(pathVars[i]), StandardCharsets.UTF_8);
            result = result.replace("{" + varName + "}", encoded);
        }
        return result;
    }

    private static List<Parameter> pathVariableParams(Method method) {
        return java.util.Arrays.stream(method.getParameters())
                .filter(p -> p.isAnnotationPresent(PathVariable.class))
                .toList();
    }

    private static String resolvePathVariableName(Parameter p) {
        PathVariable pv = p.getAnnotation(PathVariable.class);
        if (!pv.value().isEmpty()) {
            return pv.value();
        }
        if (!pv.name().isEmpty()) {
            return pv.name();
        }
        // Fall back to the reflective parameter name — requires -parameters at compile time;
        // if it was not supplied, Java reports it as "arg0" and the substitution will no-op.
        return p.getName();
    }

    /**
     * The outcome of resolving a controller method reference.
     */
    record Resolved(HttpMethod httpMethod, String controllerPath, Class<?> controllerClass) {
    }
}
