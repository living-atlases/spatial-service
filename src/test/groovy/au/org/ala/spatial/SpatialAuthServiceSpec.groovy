package au.org.ala.spatial

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Role checks used by LoginInterceptor, and by controllers to decide whether a user can see or
 * change objects owned by other users.
 */
class SpatialAuthServiceSpec extends Specification {

    SpatialAuthService service = new SpatialAuthService()

    def setup() {
        service.spatialConfig = new SpatialConfig(auth: new SpatialConfig.DotRole(admin_role: 'ROLE_SPATIAL_ADMIN'))
    }

    /** authService with userInRole() answering from `roles`, and a delegate 'role' attribute of `attribute`. */
    private void user(List<String> roles, Object attribute = null, boolean delegateFails = false) {
        def delegate = new Expando(getAttribute: { String name ->
            if (delegateFails) throw new IllegalStateException('no user')
            name == 'role' ? attribute : null
        })
        service.authService = new Expando(
                userInRole: { String role -> role in roles },
                delegateService: delegate)
    }

    def "a role in the user details is accepted"() {
        given:
        user(['ROLE_USER'])

        expect:
        service.userInRole('ROLE_USER')
        !service.userInRole('ROLE_SPATIAL_ADMIN')
    }

    @Unroll
    def "a role only present in the 'role' attribute #attribute is accepted"() {
        given:
        user([], attribute)

        expect:
        service.userInRole('ROLE_SPATIAL_ADMIN')

        where:
        attribute << ['[ROLE_USER, ROLE_SPATIAL_ADMIN]', 'ROLE_SPATIAL_ADMIN', '[ROLE_SPATIAL_ADMIN]', 'ROLE_USER,ROLE_SPATIAL_ADMIN']
    }

    @Unroll
    def "the 'role' attribute #attribute does not grant ROLE_SPATIAL_ADMIN"() {
        given:
        user([], attribute)

        expect:
        !service.userInRole('ROLE_SPATIAL_ADMIN')

        where:
        attribute << [null, '', '[ROLE_USER]', 'ROLE_SPATIAL_ADMIN_READONLY', '[NOT_ROLE_SPATIAL_ADMIN]']
    }

    def "a failure reading the delegate attributes means not in role"() {
        given:
        user([], null, true)

        expect:
        !service.userInRole('ROLE_SPATIAL_ADMIN')
    }

    @Unroll
    def "isAdmin is #expected for roles #roles"() {
        given:
        user(roles)

        expect:
        service.isAdmin() == expected

        where:
        roles                      | expected
        []                         | false
        ['ROLE_USER']              | false
        ['ROLE_SPATIAL_ADMIN']     | true
        ['ala/internal']           | true
        ['ROLE_ADMIN']             | false
    }
}
