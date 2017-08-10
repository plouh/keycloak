package org.keycloak.testsuite.springboot;

import org.jboss.arquillian.graphene.page.Page;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.events.Details;
import org.keycloak.events.EventType;
import org.keycloak.services.Urls;
import org.keycloak.testsuite.AssertEvents;
import org.keycloak.testsuite.pages.AccountApplicationsPage;
import org.keycloak.testsuite.pages.OAuthGrantPage;
import org.keycloak.testsuite.util.ClientManager;
import org.keycloak.testsuite.util.WaitUtils;
import org.keycloak.util.TokenUtil;
import org.openqa.selenium.By;

import javax.ws.rs.core.UriBuilder;
import java.util.List;

import static org.keycloak.testsuite.util.WaitUtils.pause;

public class OfflineTokenSpringBootTest extends AbstractSpringBootTest {
    private static final String SERVLET_URI = APPLICATION_URL + "/admin/TokenServlet";

    @Rule
    public AssertEvents events = new AssertEvents(this);

    @Page
    private AccountApplicationsPage accountAppPage;

    @Page
    private OAuthGrantPage oauthGrantPage;

    @Test
    public void testTokens() {
        String servletUri = UriBuilder.fromUri(SERVLET_URI)
                .queryParam(OAuth2Constants.SCOPE, OAuth2Constants.OFFLINE_ACCESS)
                .build().toString();
        driver.navigate().to(servletUri);

        Assert.assertTrue("Must be on login page", loginPage.isCurrent());
        loginPage.login(USER_LOGIN, USER_PASSWORD);

        WaitUtils.waitUntilElement(By.tagName("body")).is().visible();

        Assert.assertTrue(tokenPage.isCurrent());

        Assert.assertEquals(tokenPage.getRefreshToken().getType(), TokenUtil.TOKEN_TYPE_OFFLINE);
        Assert.assertEquals(tokenPage.getRefreshToken().getExpiration(), 0);

        String accessTokenId = tokenPage.getAccessToken().getId();
        String refreshTokenId = tokenPage.getRefreshToken().getId();

        setAdapterAndServerTimeOffset(9999, SERVLET_URI);

        driver.navigate().to(SERVLET_URI);
        Assert.assertTrue("Must be on tokens page", tokenPage.isCurrent());
        Assert.assertNotEquals(tokenPage.getRefreshToken().getId(), refreshTokenId);
        Assert.assertNotEquals(tokenPage.getAccessToken().getId(), accessTokenId);

        setAdapterAndServerTimeOffset(0, SERVLET_URI);

        driver.navigate().to(logoutPage(SERVLET_URI));
        Assert.assertTrue("Must be on login page", loginPage.isCurrent());
    }

    @Test
    public void testRevoke() {
        // Login to servlet first with offline token
        String servletUri = UriBuilder.fromUri(SERVLET_URI)
                .queryParam(OAuth2Constants.SCOPE, OAuth2Constants.OFFLINE_ACCESS)
                .build().toString();
        driver.navigate().to(servletUri);
        WaitUtils.waitUntilElement(By.tagName("body")).is().visible();

        loginPage.login(USER_LOGIN, USER_PASSWORD);
        Assert.assertTrue("Must be on token page", tokenPage.isCurrent());

        Assert.assertEquals(tokenPage.getRefreshToken().getType(), TokenUtil.TOKEN_TYPE_OFFLINE);

        // Assert refresh works with increased time
        setAdapterAndServerTimeOffset(9999, SERVLET_URI);
        driver.navigate().to(SERVLET_URI);
        Assert.assertTrue("Must be on token page", tokenPage.isCurrent());
        setAdapterAndServerTimeOffset(0, SERVLET_URI);

        events.clear();

        // Go to account service and revoke grant
        accountAppPage.open();

        List<String> additionalGrants = accountAppPage.getApplications().get(CLIENT_ID).getAdditionalGrants();
        Assert.assertEquals(additionalGrants.size(), 1);
        Assert.assertEquals(additionalGrants.get(0), "Offline Token");
        accountAppPage.revokeGrant(CLIENT_ID);
        pause(500);
        Assert.assertEquals(accountAppPage.getApplications().get(CLIENT_ID).getAdditionalGrants().size(), 0);

        events.expect(EventType.REVOKE_GRANT).realm(REALM_ID).user(getCorrectUserId())
                .client("account").detail(Details.REVOKED_CLIENT, CLIENT_ID).assertEvent();

        // Assert refresh doesn't work now (increase time one more time)
        setAdapterAndServerTimeOffset(9999, SERVLET_URI);
        driver.navigate().to(SERVLET_URI);
        loginPage.assertCurrent();
        setAdapterAndServerTimeOffset(0, SERVLET_URI);
    }

    @Test
    public void testConsent() {
        ClientManager.realm(adminClient.realm(REALM_NAME)).clientId(CLIENT_ID).consentRequired(true);

        // Assert grant page doesn't have 'Offline Access' role when offline token is not requested
        driver.navigate().to(SERVLET_URI);
        loginPage.login(USER_LOGIN, USER_PASSWORD);
        oauthGrantPage.assertCurrent();
        WaitUtils.waitUntilElement(By.xpath("//body")).text().not().contains("Offline access");
        oauthGrantPage.cancel();

        // Assert grant page has 'Offline Access' role now
        String servletUri = UriBuilder.fromUri(SERVLET_URI)
                .queryParam(OAuth2Constants.SCOPE, OAuth2Constants.OFFLINE_ACCESS)
                .build().toString();
        driver.navigate().to(servletUri);
        WaitUtils.waitUntilElement(By.tagName("body")).is().visible();

        loginPage.login(USER_LOGIN, USER_PASSWORD);
        oauthGrantPage.assertCurrent();
        WaitUtils.waitUntilElement(By.xpath("//body")).text().contains("Offline access");

        oauthGrantPage.accept();

        Assert.assertTrue("Must be on token page", tokenPage.isCurrent());
        Assert.assertEquals(tokenPage.getRefreshToken().getType(), TokenUtil.TOKEN_TYPE_OFFLINE);

        String accountAppPageUrl =
            Urls.accountApplicationsPage(getAuthServerRoot(), REALM_NAME).toString();
        driver.navigate().to(accountAppPageUrl);
        AccountApplicationsPage.AppEntry offlineClient = accountAppPage.getApplications().get(CLIENT_ID);
        Assert.assertTrue(offlineClient.getRolesGranted().contains("Offline access"));
        Assert.assertTrue(offlineClient.getAdditionalGrants().contains("Offline Token"));

        //This was necessary to be introduced, otherwise other testcases will fail
        driver.navigate().to(logoutPage(SERVLET_URI));
        loginPage.assertCurrent();

        events.clear();

        // Revert change
        ClientManager.realm(adminClient.realm(REALM_NAME)).clientId(CLIENT_ID).consentRequired(false);
    }
}
