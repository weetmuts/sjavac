// Copyright 2014 by Fredrik Öhrström and licensed to you 
// under the GPLv2 with the classpath exception.
package com.sun.tools.sjavac.server;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

public class PublicApiResult implements Serializable {

    static final long serialVersionUID = 974918273790L;

    public List<String> api;
    public Set<String> archives;
}
