package hudson.plugins.audit_trail;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import jenkins.model.GlobalConfiguration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author <a href="mailto:alexander.russell@sap.com">Alex Russell</a>
 */
public class ElasticSearchAuditLoggerTest {

    private static String esUrl = "https://localhost:21699/myindex/jenkins";
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void shouldConfigureElasticSearchAuditLogger() throws Exception {
        JenkinsRule.WebClient jenkinsWebClient = jenkinsRule.createWebClient();
        HtmlPage configure = jenkinsWebClient.goTo("configure");
        HtmlForm form = configure.getFormByName("config");
        jenkinsRule.getButtonByCaption(form, "Add Logger").click();
        configure.getAnchorByText("Elastic Search server").click();
        jenkinsWebClient.waitForBackgroundJavaScript(2000);

        // When
        jenkinsRule.submit(form);

        // Then
        // submit configuration page without any errors
        AuditTrailPlugin plugin = GlobalConfiguration.all().get(AuditTrailPlugin.class);
        assertEquals("amount of loggers", 1, plugin.getLoggers().size());
        AuditLogger logger = plugin.getLoggers().get(0);
        assertTrue("ConsoleAuditLogger should be configured", logger instanceof ElasticSearchAuditLogger);
    }

    @Test
    @Ignore
    public void testElasticSearchAuditLogger() throws Exception {
        String url = ElasticSearchAuditLogger.addTodaysDateToURL(esUrl);
        assertEquals("opensearch url", "https://localhost:21699/myindex.2022-06-07/jenkinslogs", url);
    }
}
