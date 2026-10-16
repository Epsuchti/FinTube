package ch.it4user.fintube.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExceptionLoggingResolver implements HandlerExceptionResolver {
    private static final Logger LOG = LoggerFactory.getLogger(ExceptionLoggingResolver.class);

    @Override
    public ModelAndView resolveException(HttpServletRequest request,
                                         HttpServletResponse response,
                                         Object handler,
                                         Exception exception) {
        String handlerName = handler == null ? "<unknown>" : handler.getClass().getName();
        if (clientDisconnected(exception)) {
            // Players commonly cancel an in-flight segment when seeking,
            // changing quality, or closing playback. The response has already
            // become unusable, but this is not an application or upstream
            // media failure and should not produce a misleading ERROR stack.
            LOG.debug("event=HTTP_CLIENT_DISCONNECTED method={} path={} handler={} reason={}",
                    request.getMethod(), request.getRequestURI(), handlerName, exception.getMessage());
            return null;
        }
        if (exception instanceof ResponseStatusException statusException) {
            LOG.error("Request failed: method={} path={} status={} reason={} handler={}",
                    request.getMethod(), request.getRequestURI(), statusException.getStatusCode(),
                    statusException.getReason(), handlerName, exception);
        } else {
            LOG.error("Request failed: method={} path={} handler={}",
                    request.getMethod(), request.getRequestURI(), handlerName, exception);
        }
        return null;
    }

    private static boolean clientDisconnected(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            String type = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(type)
                    || "org.springframework.web.context.request.async.AsyncRequestNotUsableException".equals(type)) {
                return true;
            }
        }
        return false;
    }
}
