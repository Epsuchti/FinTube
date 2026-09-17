package ch.it4user.fintube.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** Sends browser routes to Angular while keeping API and actuator routes server-side. */
@Controller
public class SpaForwardingController {
    @RequestMapping(value = {"/", "/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String forward() {
        return "forward:/index.html";
    }
}
