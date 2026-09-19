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
}
