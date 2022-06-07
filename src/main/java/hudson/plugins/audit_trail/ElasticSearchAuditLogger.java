/*
 * The MIT License
 *
 * Copyright 2014 Barnes and Noble College
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.audit_trail;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AuditLogger implementation to send audit logs to an Elastic Search server.
 * Some code take from the Jenkins logstash plugin: https://github.com/jenkinsci/logstash-plugin
 * <p>
 * Default values are set in <code>/src/main/resources/hudson/plugins/audit_trail/ElasticSearchAuditLogger/config.jelly</code>
 *
 * @author <a href="mailto:alexander.russell@sap.com">Alex Russell</a>
 */
public class ElasticSearchAuditLogger extends AuditLogger {

    private String url;
    private String usernamePasswordCredentialsId;
    private String clientCertificateCredentialsId;
    private boolean skipCertificateValidation = false;

    transient ElasticSearchSender elasticSearchSender;

    protected static final Logger LOGGER = Logger.getLogger(ElasticSearchAuditLogger.class.getName());
    private static final FastDateFormat DATE_FORMATTER = FastDateFormat.getInstance("yyyy-MM-dd'T'HH:mm:ssZ");

    private static final FastDateFormat SIMPLE_DATE_FORMATTER = FastDateFormat.getInstance("yyyy-MM-dd");

    @DataBoundConstructor
    public ElasticSearchAuditLogger(String url, boolean skipCertificateValidation) {
        this.url = url;
        this.skipCertificateValidation = skipCertificateValidation;
    }

    private Object readResolve() {
        configure();
        return this;
    }

    @Override
    public void log(String event) {
        if (elasticSearchSender == null) {
            // Create the sender because it might not have been created when Jenkins started
            // The reason for it not being created seems to be because the credentials have not been loaded yet
            configure();
            if (elasticSearchSender == null) {
                LOGGER.log(Level.FINER, "skip log {0}, elasticSearchSender not configured", event);
                return;
            }
        }
        LOGGER.log(Level.FINER, "Send audit message \"{0}\" to Elastic Search server {1}", new Object[]{event, elasticSearchSender.getUrl()});
        try {
            elasticSearchSender.sendMessage(event);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Audit event not sent to Elastic Search server: " + event + " - " + elasticSearchSender.toString(), e);
        }
    }

    public void configure() {
        if (url == null || url.length() == 0) {
            LOGGER.fine("Elastic Search Logger not configured");
            return;
        }
        String username = null;
        String password = null;
        if (!StringUtils.isBlank(usernamePasswordCredentialsId)) {
            LOGGER.fine("Username/password credentials specified: " + usernamePasswordCredentialsId);
            StandardUsernamePasswordCredentials usernamePasswordCredentials = getUsernamePasswordCredentials(usernamePasswordCredentialsId);
            if (usernamePasswordCredentials != null) {
                username = usernamePasswordCredentials.getUsername();
                password = Secret.toString(usernamePasswordCredentials.getPassword());
            }
        }
        KeyStore clientKeyStore = null;
        String clientKeyStorePassword = null;
        if (!StringUtils.isBlank(clientCertificateCredentialsId)) {
            LOGGER.fine("Client certificate specified: " + clientCertificateCredentialsId);
            StandardCertificateCredentials certificateCredentials = getCertificateCredentials(clientCertificateCredentialsId);
            if (certificateCredentials != null) {
                clientKeyStore = certificateCredentials.getKeyStore();
                clientKeyStorePassword = certificateCredentials.getPassword().getPlainText();
                LOGGER.fine("Client certificate keystore loaded");
            } else {
                LOGGER.log(Level.SEVERE, "Unable to find certificate credentials: " + clientCertificateCredentialsId + " - Not creating ElasticSearchSender");
                return;
            }
        }
        // Create the sender for Elastic Search
        try {
            elasticSearchSender = new ElasticSearchSender(url, username, password, clientKeyStore, clientKeyStorePassword, skipCertificateValidation);
            LOGGER.log(Level.FINE, "ElasticSearchAuditLogger: {0}", this);
        } catch (IOException ioe) {
            LOGGER.log(Level.SEVERE, "Unable to create ElasticSearchSender", ioe);
        } catch (GeneralSecurityException gse) {
            LOGGER.log(Level.SEVERE, "Unable to create ElasticSearchSender", gse);
        }
    }

    /**
     * Returns the usernamePassword credential with the specified id.
     *
     * @param credentialsId The id of the usernamePassword credential to find
     * @return The credentials object or null if not found
     */
    private StandardUsernamePasswordCredentials getUsernamePasswordCredentials(String credentialsId) {
        return (StandardUsernamePasswordCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class,
                        Jenkins.getInstance(), ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId)
        );
    }

    /**
     * Returns the certificate credential with the specified id.
     *
     * @param credentialsId The id of the certificate credential to find
     * @return The credentials object or null if not found
     */
    private StandardCertificateCredentials getCertificateCredentials(String credentialsId) {
        return (StandardCertificateCredentials) CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(StandardCertificateCredentials.class,
                        Jenkins.getInstance(), ACL.SYSTEM, Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId)
        );
    }

    public String getUrl() {
        return url;
    }

    @DataBoundSetter
    public void setUrl(String url) throws URISyntaxException, MalformedURLException {
        this.url = url;
        new URL(url).toURI();
    }

    public String getUsernamePasswordCredentialsId() {
        return usernamePasswordCredentialsId;
    }

    @DataBoundSetter
    public void setUsernamePasswordCredentialsId(String usernamePasswordCredentialsId) {
        this.usernamePasswordCredentialsId = usernamePasswordCredentialsId;
    }

    public String getClientCertificateCredentialsId() {
        return clientCertificateCredentialsId;
    }

    @DataBoundSetter
    public void setClientCertificateCredentialsId(String clientCertificateCredentialsId) {
        this.clientCertificateCredentialsId = clientCertificateCredentialsId;
    }

    public boolean getSkipCertificateValidation() {
        return skipCertificateValidation;
    }

    @DataBoundSetter
    public void setSkipCertificateValidation(boolean skipCertificateValidation) {
        this.skipCertificateValidation = skipCertificateValidation;
    }

    public String getDisplayName() {
        return "Elastic Search Logger";
    }

    ElasticSearchSender getElasticSearchSender() {
        return elasticSearchSender;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ElasticSearchAuditLogger)) return false;

        ElasticSearchAuditLogger that = (ElasticSearchAuditLogger) o;

        if (url != null ? !url.equals(that.url) : that.url != null) {
            return false;
        }
        if (usernamePasswordCredentialsId != null ? !usernamePasswordCredentialsId.equals(that.usernamePasswordCredentialsId) : that.usernamePasswordCredentialsId != null) {
            return false;
        }
        if (clientCertificateCredentialsId != null ? !clientCertificateCredentialsId.equals(that.clientCertificateCredentialsId) : that.clientCertificateCredentialsId != null) {
            return false;
        }
        if (skipCertificateValidation != that.skipCertificateValidation) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + ((url == null) ? 0 : url.hashCode());
        result = prime * result + ((usernamePasswordCredentialsId == null) ? 0 : usernamePasswordCredentialsId.hashCode());
        result = prime * result + ((clientCertificateCredentialsId == null) ? 0 : clientCertificateCredentialsId.hashCode());
        result = prime * result + Boolean.hashCode(skipCertificateValidation);
        return result;
    }

    @Override
    public String toString() {
        return "ElasticSearchAuditLogger{" +
                "url='" + url + "'" +
                ", usernamePasswordCredentialsId='" + usernamePasswordCredentialsId + "'" +
                ", clientCertificateCredentialsId='" + clientCertificateCredentialsId + "'" +
                ", skipCertificateValidation='" + skipCertificateValidation + "'" +
                "}";
    }

    /**
     * Class to do the work of authenticating to the Elastic Search server and
     * sending log messages to it.
     */
    static class ElasticSearchSender {
        private final CloseableHttpClient httpClient;

        private final String url;
        private final String auth;
        private final boolean skipCertificateValidation;

        public ElasticSearchSender(String url, String username, String password, KeyStore clientKeyStore, String clientKeyStorePassword, boolean skipCertificateValidation) throws IOException, GeneralSecurityException {
            this.url = url;
            if (StringUtils.isNotBlank(username)) {
                auth = Base64.encodeBase64String((username + ":" + StringUtils.defaultString(password)).getBytes(StandardCharsets.UTF_8));
            } else {
                auth = null;
            }
            this.skipCertificateValidation = skipCertificateValidation;
            httpClient = createHttpClient(clientKeyStore, clientKeyStorePassword, skipCertificateValidation);
        }

        public String getUrl() {
            return url;
        }

        public boolean getSkipCertificateValidation() {
            return skipCertificateValidation;
        }

        private CloseableHttpClient createHttpClient(KeyStore keyStore, String keyStorePassword, boolean skipCertificateValidation)
                throws IOException, GeneralSecurityException {
            TrustStrategy trustStrategy = null;
            if (skipCertificateValidation) {
                trustStrategy = new TrustSelfSignedStrategy();
            }
            SSLContextBuilder contextBuilder = SSLContexts.custom();
            contextBuilder.loadTrustMaterial(keyStore, trustStrategy);
            if (keyStore != null) {
                contextBuilder.loadKeyMaterial(keyStore, keyStorePassword.toCharArray());
            }
            SSLContext sslContext = contextBuilder.build();
            HttpClientBuilder builder = HttpClients.custom();
            builder.setSslcontext(sslContext);
            if (skipCertificateValidation) {
                builder.setSSLHostnameVerifier(new NoopHostnameVerifier());
            }
            return builder.build();
        }

        public void sendMessage(String event) throws IOException {
            HttpPost post = getHttpPost(event);
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    LOGGER.log(Level.FINE, "Response: {0}", response.toString());
                } else {
                    throw new IOException(this.getErrorMessage(response));
                }
            }
        }

        HttpPost getHttpPost(String data) {
            String urlWithDate = addTodaysDateToURL(url);
            HttpPost postRequest = new HttpPost(urlWithDate);
            System.out.println("url " + url);
            System.out.println("urlWithDate " + urlWithDate);
            // char encoding is set to UTF_8 since this request posts a JSON string
            JSONObject payload = new JSONObject();
            payload.put("message", data);
            payload.put("@timestamp", DATE_FORMATTER.format(Calendar.getInstance().getTime()));
            StringEntity input = new StringEntity(payload.toString(), StandardCharsets.UTF_8);
            input.setContentType(ContentType.APPLICATION_JSON.toString());
            postRequest.setEntity(input);
            if (auth != null) {
                postRequest.addHeader("Authorization", "Basic " + auth);
            }
            return postRequest;
        }

        private String getErrorMessage(CloseableHttpResponse response) {
            ByteArrayOutputStream byteStream = null;
            PrintStream stream = null;
            try {
                byteStream = new ByteArrayOutputStream();
                stream = new PrintStream(byteStream, true, StandardCharsets.UTF_8.name());
                try {
                    stream.print("HTTP error code: ");
                    stream.println(response.getStatusLine().getStatusCode());
                    stream.print("URL: ");
                    stream.println(url);
                    stream.println("RESPONSE: " + response);
                    response.getEntity().writeTo(stream);
                } catch (IOException e) {
                    stream.println(ExceptionUtils.getStackTrace(e));
                }
                stream.flush();
                return byteStream.toString(StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                return ExceptionUtils.getStackTrace(e);
            } finally {
                if (stream != null) {
                    stream.close();
                }
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AuditLogger> {

        @Override
        public String getDisplayName() {
            return "Elastic Search server";
        }

        public ListBoxModel doFillUsernamePasswordCredentialsIdItems(
                @QueryParameter String usernamePasswordCredentialsId,
                @QueryParameter String url) {
            if (!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(usernamePasswordCredentialsId);
            }
            List<DomainRequirement> domainRequirements = URIRequirementBuilder.fromUri(url).build();
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            Jenkins.getInstance(),
                            StandardCredentials.class,
                            domainRequirements,
                            CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)))
                    .includeCurrentValue(usernamePasswordCredentialsId);
        }

        public ListBoxModel doFillClientCertificateCredentialsIdItems(
                @QueryParameter String clientCertificateCredentialsId,
                @QueryParameter String url) {
            if (!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(clientCertificateCredentialsId);
            }
            List<DomainRequirement> domainRequirements = URIRequirementBuilder.fromUri(url).build();
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            Jenkins.getInstance(),
                            StandardCertificateCredentials.class,
                            domainRequirements,
                            CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardCertificateCredentials.class)))
                    .includeCurrentValue(clientCertificateCredentialsId);
        }

        public FormValidation doCheckUrl(@QueryParameter("value") String value) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warning("URL must be set");
            }
            try {
                URL url = new URL(value);

                if (url.getUserInfo() != null) {
                    return FormValidation.error("Please specify user and password not as part of the url.");
                }

                if (StringUtils.isBlank(url.getPath()) || url.getPath().trim().matches("^\\/+$")) {
                    return FormValidation.warning("Elastic Search requires an index name and document type to be able to index the logs.  eg. https://elastic.mydomain.com/myindex/jenkinslog/");
                }

                url.toURI();
            } catch (MalformedURLException | URISyntaxException e) {
                return FormValidation.error(e.getMessage());
            }
            return FormValidation.ok();
        }
    }

    public static String addTodaysDateToURL(String elasticUrl) {
        URL url;
        try {
            url = new URL(elasticUrl);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        String path = url.getPath();
        String[] parts = path.split("/");
        String indexName = parts[1];
        String today = SIMPLE_DATE_FORMATTER.format(new Date());
        indexName = indexName + "." + today;
        return "https://" + url.getHost() + ":" + url.getPort() + "/" + indexName + "/jenkinslogs";
    }

}
