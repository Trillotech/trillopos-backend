package app.trillopos.auth;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import app.trillopos.support.Api;
import app.trillopos.support.Api.Owner;
import app.trillopos.support.IntegrationTest;

class StaffAccessTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    Api api;

    @BeforeEach void client() { api = new Api(mvc); }

    String invite(Owner owner, String role) throws Exception {
        return read(api.call(json(post("/memberships"),
                "{\"displayName\":\"Staff\",\"role\":\"%s\"}".formatted(role)), owner.token()), "$.inviteCode");
    }

    String joinBody(String code, String phone) {
        return """
                {"code":"%s","phone":"%s","password":"staff-password","fullName":"Staff"}
                """.formatted(code, phone);
    }

    @Test void joiningCreatesOnlyAnAccountAndKeepsTheInvitedRole() throws Exception {
        Owner owner = api.signup();
        for (String role : new String[] { "CASHIER", "STOCK_MANAGER", "PACKER" }) {
            String code = invite(owner, role);
            String phone = Api.randomPhone();
            String token = read(mvc.perform(json(post("/auth/join"), joinBody(code, phone)))
                    .andExpect(status().isCreated()), "$.accessToken");
            api.call(get("/session"), token).andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(role))
                    .andExpect(jsonPath("$.organizationName").value("Api Shop"));
            api.call(get("/auth/memberships"), token).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].role").value(role));
            api.call(get("/memberships"), token).andExpect(status().isForbidden());
            api.call(json(post("/products"), "{\"name\":\"Blocked\",\"unit\":\"PIECE\",\"retailPrice\":100}"), token)
                    .andExpect(role.equals("STOCK_MANAGER") ? status().isCreated() : status().isForbidden());
            api.call(get("/reports/summary"), token)
                    .andExpect(role.equals("STOCK_MANAGER") ? status().isOk() : status().isForbidden());
            mvc.perform(json(post("/auth/login"), """
                    {"phone":"%s","password":"staff-password"}""".formatted(phone)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.tokens.kind").value("USER"));
            mvc.perform(json(post("/auth/join"), joinBody(code, Api.randomPhone())))
                    .andExpect(status().isNotFound());
        }
    }

    @Test void invalidCodeCreatesNoAccountAndExistingAccountsMustSignIn() throws Exception {
        String phone = Api.randomPhone();
        mvc.perform(json(post("/auth/join"), joinBody("INVALID", phone))).andExpect(status().isNotFound());
        mvc.perform(json(post("/auth/login"), """
                {"phone":"%s","password":"staff-password"}""".formatted(phone)))
                .andExpect(status().isUnauthorized());
        Owner owner = api.signup();
        String code = invite(owner, "CASHIER");
        mvc.perform(json(post("/auth/join"), joinBody(code, phone))).andExpect(status().isCreated());
        Owner other = api.signup();
        String otherCode = invite(other, "PACKER");
        mvc.perform(json(post("/auth/join"), joinBody(otherCode, phone))).andExpect(status().isConflict());
        String token = read(mvc.perform(json(post("/auth/login"), """
                {"phone":"%s","password":"staff-password"}""".formatted(phone))), "$.tokens.accessToken");
        api.call(json(post("/auth/invitations/accept"), "{\"code\":\"%s\"}".formatted(otherCode)), token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("PACKER"));
    }

    @Test void registerSessionCanReadItsOwnIdentityAndRefreshWithoutAnAccount() throws Exception {
        Owner owner = api.signup();
        String id = read(api.call(json(post("/memberships"),
                "{\"displayName\":\"Counter staff\",\"role\":\"CASHIER\",\"pin\":\"482913\"}"), owner.token()), "$.membership.id");
        String device = read(api.call(json(post("/registers"), """
                {"locationId":"%s","label":"Counter"}""".formatted(owner.mainLocationId())), owner.token()), "$.deviceCredential");
        var login = mvc.perform(json(post("/auth/pin"),
                "{\"membershipId\":\"%s\",\"pin\":\"482913\"}".formatted(id)).header("X-Register-Device", device))
                .andExpect(status().isOk());
        String token = read(login, "$.accessToken");
        api.call(get("/session"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Counter staff"))
                .andExpect(jsonPath("$.role").value("CASHIER"))
                .andExpect(jsonPath("$.kind").value("REGISTER"))
                .andExpect(jsonPath("$.locationId").value(owner.mainLocationId()));
        api.call(get("/auth/memberships"), token).andExpect(status().isForbidden());
        String renewed = read(mvc.perform(json(post("/auth/refresh"),
                "{\"refreshToken\":\"%s\"}".formatted(read(login, "$.refreshToken"))))
                .andExpect(status().isOk()), "$.accessToken");
        api.call(get("/session"), renewed).andExpect(status().isOk());
        mvc.perform(get("/session")).andExpect(status().isUnauthorized());
    }
}
