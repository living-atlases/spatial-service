/*
 * Copyright (C) 2016 Atlas of Living Australia
 * All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 */

package au.org.ala.spatial.service

import au.org.ala.layers.dto.Layer
import com.sun.istack.internal.logging.Logger
import grails.converters.JSON
import org.codehaus.groovy.grails.web.json.JSONArray
import org.springframework.util.StreamUtils

import java.nio.charset.Charset

class TestUtil {
    final static Logger log = Logger.getLogger(TestUtil.class)

    static String getResourceAsString(String path) {
        return StreamUtils.copyToString(LayerDistancesServiceSpec.class.getResourceAsStream("/resources/$path"),
                Charset.forName("UTF-8"))
    }

    static String getResourcePath(String path) {
        LayerDistancesServiceSpec.class.getResource("/resources/$path").file
    }

    //
    static <T> List<T> getListFromJSON(String path, Class<T> clazz) {
        def json = (JSONArray) JSON.parse(getResourceAsString(path))

        try {
            List<Layer> list = json.collect { o ->
                T f = clazz.newInstance()
                o.each { k, v ->
                    //set value with Setter
                    def df = clazz.getDeclaredField(k.toString())
                    if (df != null) {
                        def dm = clazz.getDeclaredMethod("set" + k.toString().substring(0, 1).toUpperCase() + k.toString().substring(1, k.toString().length()),
                                (Class[]) [df.getType()])
                        if (dm != null) {
                            if (df.getType().isPrimitive()) {
                                dm.invoke(f, v)
                            } else {
                                dm.invoke(f, df.getType().newInstance(v))
                            }
                        }
                    }
                }
                f
            }

            return list
        } catch (Exception e) {
            e.printStackTrace()
        }

        return null
    }

}
