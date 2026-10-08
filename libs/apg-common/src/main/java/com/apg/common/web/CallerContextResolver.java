package com.apg.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers declare a {@link CallerContext} parameter. */
public class CallerContextResolver implements HandlerMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter p) { return CallerContext.class.equals(p.getParameterType()); }

    @Override
    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) {
        Object ctx = r.getAttribute(CallerContext.ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        if (ctx == null) throw new IllegalStateException("CallerContext missing: apg.internal.token not configured?");
        return ctx;
    }
}
