package au.org.ala.spatial

import au.org.ala.web.AuthService
import grails.artefact.Artefact
import grails.testing.web.interceptor.InterceptorUnitTest
import spock.lang.Specification
import spock.lang.Unroll

/**
 * LoginInterceptor enforces @RequireLogin / @RequireAdmin / @SkipSecurityCheck on every action.
 * API callers (Accept: application/json) get a JSON 401/403, browsers are redirected to the login page.
 */
class LoginInterceptorSpec extends Specification implements InterceptorUnitTest<LoginInterceptor> {

    AuthService authService = Mock()
    SpatialAuthService spatialAuthService = Mock()

    def setup() {
        [SecuredTestController, ClassLoginTestController, ClassAdminTestController].each {
            grailsApplication.addArtefact('Controller', it)
        }
        interceptor.authService = authService
        interceptor.spatialAuthService = spatialAuthService
        interceptor.spatialConfig = config(true, false)
        authService.loginUrl(_) >> 'https://auth.example.org/login'
    }

    private static SpatialConfig config(boolean cas, boolean oidc) {
        new SpatialConfig(
                security: new SpatialConfig.DotSecurity(
                        cas: new SpatialConfig.DotEnabled(enabled: cas),
                        oidc: new SpatialConfig.DotEnabled(enabled: oidc)),
                auth: new SpatialConfig.DotRole(admin_role: 'ROLE_ADMIN'))
    }

    private void route(String controller, String action, boolean json = true) {
        webRequest.controllerName = controller
        webRequest.actionName = action
        if (json) request.addHeader('Accept', 'application/json')
    }

    def "the interceptor matches every controller"() {
        when:
        withRequest(controller: 'securedTest', action: 'open')

        then:
        interceptor.doesMatch()
    }

    @Unroll
    def "with CAS=#cas and OIDC=#oidc an admin action is #outcome for an anonymous user"() {
        given:
        interceptor.spatialConfig = config(cas, oidc)
        route('securedTest', 'admin')

        when:
        def result = interceptor.before()

        then:
        result == allowed

        where:
        cas   | oidc  | allowed
        true  | false | false
        false | true  | false
        true  | true  | false
        // the no-SSO mode (see resources.groovy) is a development setup only
        false | false | true
        outcome = allowed ? 'allowed' : 'denied'
    }

    def "actions without an annotation are open"() {
        given:
        route('securedTest', 'open')

        when:
        def result = interceptor.before()

        then:
        result
        0 * authService.getUserId()
    }

    def "@SkipSecurityCheck is open even in a controller that requires a login"() {
        given:
        route('classLoginTest', 'skipped')

        when:
        def result = interceptor.before()

        then:
        result
        0 * authService.getUserId()
    }

    @Unroll
    def "anonymous API request to a #annotation action gets a JSON 401"() {
        given:
        route(controller, action)

        when:
        def result = interceptor.before()

        then:
        1 * authService.getUserId() >> null
        !result
        response.status == 401
        response.json.error

        where:
        annotation               | controller         | action
        '@RequireLogin'          | 'securedTest'      | 'login'
        '@RequireAdmin'          | 'securedTest'      | 'admin'
        'class @RequireLogin'    | 'classLoginTest'   | 'inherited'
        'class @RequireAdmin'    | 'classAdminTest'   | 'inherited'
    }

    def "anonymous browser request to a protected action is redirected to the login page"() {
        given:
        route('securedTest', 'login', false)

        when:
        def result = interceptor.before()

        then:
        1 * authService.getUserId() >> null
        !result
        response.redirectedUrl == 'https://auth.example.org/login'
    }

    def "a signed in user can use a @RequireLogin action"() {
        given:
        route('securedTest', 'login')

        when:
        def result = interceptor.before()

        then:
        1 * authService.getUserId() >> '42'
        0 * spatialAuthService.isAdmin()
        result
    }

    @Unroll
    def "a signed in #who gets #status from a #annotation action"() {
        given:
        route(controller, action)

        when:
        def result = interceptor.before()

        then:
        1 * authService.getUserId() >> '42'
        1 * spatialAuthService.isAdmin() >> admin
        result == admin
        admin || response.status == 403

        where:
        annotation              | controller       | action      | admin
        '@RequireAdmin'         | 'securedTest'    | 'admin'     | false
        '@RequireAdmin'         | 'securedTest'    | 'admin'     | true
        'class @RequireAdmin'   | 'classAdminTest' | 'inherited' | false
        'class @RequireAdmin'   | 'classAdminTest' | 'inherited' | true
        '@RequireLogin(role)'   | 'securedTest'    | 'loginRole' | false
        '@RequireLogin(role)'   | 'securedTest'    | 'loginRole' | true
        who = admin ? 'admin' : 'non admin user'
        status = admin ? 'access' : 403
    }

    def "a method annotation takes priority over the class annotation"() {
        given:
        route('classAdminTest', 'loginOnly')

        when:
        def result = interceptor.before()

        then:
        1 * authService.getUserId() >> '42'
        0 * spatialAuthService.isAdmin()
        result
    }
}

@Artefact('Controller')
class SecuredTestController {
    def open() { render 'ok' }

    @RequireLogin
    def login() { render 'ok' }

    @RequireLogin(role = 'ROLE_ADMIN')
    def loginRole() { render 'ok' }

    @RequireAdmin
    def admin() { render 'ok' }
}

@Artefact('Controller')
@RequireLogin
class ClassLoginTestController {
    def inherited() { render 'ok' }

    @SkipSecurityCheck
    def skipped() { render 'ok' }
}

@Artefact('Controller')
@RequireAdmin
class ClassAdminTestController {
    def inherited() { render 'ok' }

    @RequireLogin
    def loginOnly() { render 'ok' }
}
