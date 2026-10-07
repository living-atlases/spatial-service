package au.org.ala.spatial

import au.org.ala.spatial.dto.SearchObject
import au.org.ala.spatial.dto.Tabulation
import grails.converters.JSON
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection

/**
 * The JSON marshallers that BootStrap registers at startup, for controller unit tests
 * (keep in sync with grails-app/init/au/org/ala/spatial/BootStrap.groovy).
 */
class BootStrapMarshallers {

    static void register() {
        [Geometry, GeometryCollection].each {
            JSON.registerObjectMarshaller(it) {
                it?.toString()
            }
        }

        [Fields, Distributions, InputParameter, Layers, Log, OutputParameter, SpatialObjects, Task].each {
            JSON.registerObjectMarshaller(it) {
                def id = it.id ?: it.pid ?: it.spcode
                def result = it.properties.findAll { it.key != 'class' && it.key != 'version' && it.value != null }
                if (id) {
                    result += [id: id]
                }
                if (it instanceof Layers && result.containsKey("dt_added")) {
                    result += [dt_added: ((Layers) it).dt_added.time]
                }
                result
            }
        }

        [Tabulation, SearchObject].each {
            JSON.registerObjectMarshaller(it) {
                it.properties.findAll { it.key != 'class' && it.key != 'version' && it.value != null }
            }
        }
    }
}
