package app.trillopos.auth;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import app.trillopos.org.MembershipRepository;
import app.trillopos.org.MembershipRole;
import app.trillopos.org.OrganizationRepository;
import app.trillopos.shared.tenant.TenantContext;

/** The verified current tenant session, including account-less register staff. */
@RestController
class SessionController {
    record SessionView(String kind, UUID organizationId, String organizationName, UUID membershipId,
            String displayName, MembershipRole role, UUID locationId) {
    }

    private final MembershipRepository memberships;
    private final OrganizationRepository organizations;

    SessionController(MembershipRepository memberships, OrganizationRepository organizations) {
        this.memberships = memberships;
        this.organizations = organizations;
    }

    @GetMapping("/session")
    SessionView current(@AuthenticationPrincipal Jwt jwt) {
        var membership = memberships.findById(TenantContext.requireMembershipId()).orElseThrow();
        var organization = organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
        return new SessionView(jwt.getClaimAsString(TokenService.KIND), organization.getId(), organization.getName(),
                membership.getId(), membership.getDisplayName(), membership.getRole(),
                TenantContext.current().map(TenantContext.Current::locationScope).orElse(null));
    }
}
