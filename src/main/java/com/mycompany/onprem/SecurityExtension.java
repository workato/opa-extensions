/*
 * Copyright (c) 2018 MyCompany, Inc. All rights reserved.
 */

package com.mycompany.onprem;

import org.apache.commons.codec.binary.Hex;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.inject.Inject;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

@Controller
public class SecurityExtension {

    @Inject
    private Environment env;

    @RequestMapping(path = "/computeDigest", method = RequestMethod.POST)
    public @ResponseBody Map<String, Object> computeDigest(@RequestBody Map<String, Object> body) throws Exception {
        Charset encoding = Charset.forName("UTF-8");
        String payload = (String) body.get("payload");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(env.getProperty("secret").getBytes(encoding));
        byte[] result = digest.digest(payload.getBytes(encoding));
        return Collections.singletonMap("signature", Hex.encodeHexString(result));
    }
}
