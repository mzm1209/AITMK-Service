package com.example.aitmk.model.api.v2;
import org.springframework.web.context.request.*;
public final class RequestIds {private RequestIds(){} public static String current(){var a=RequestContextHolder.getRequestAttributes();if(a instanceof ServletRequestAttributes s){var v=(String)s.getRequest().getAttribute("v2RequestId");if(v!=null)return v;}return java.util.UUID.randomUUID().toString();}}
