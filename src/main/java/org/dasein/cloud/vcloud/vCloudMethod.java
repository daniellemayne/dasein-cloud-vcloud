/**
 * Copyright (C) 2009-2013 enStratus Networks Inc
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.vcloud;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.dasein.cloud.CloudErrorType;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.dc.DataCenter;
import org.dasein.cloud.dc.Region;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.TimePeriod;
import org.dasein.util.uom.time.Minute;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;

/**
 * [Class Documentation]
 * <p>Created by George Reese: 2/4/13 6:31 PM</p>
 *
 * @author George Reese
 */
public class vCloudMethod {
    static public final String[] VERSIONS = { "5.1", "1.5", "1.0", "0.9", "0.8" };

    static public final String INSTANTIATE_VAPP = "instantiateVApp";

    static public boolean isSupported(@Nonnull String version) {
        for( String v : VERSIONS ) {
            if( version.equals(v) ) {
                return true;
            }
        }
        return false;
    }

    static public boolean matches(@Nonnull String currentVersion, @Nonnull String minimumVersion, @Nullable String maximumVersion) {
        if( currentVersion.equals(minimumVersion) ) {
            return true;
        }
        else if( maximumVersion != null && currentVersion.equals(maximumVersion) ) {
            return true;
        }
        if( !isSupported(currentVersion) ) {
            return false;
        }
        boolean greaterThanMaximum = (maximumVersion != null);

        for( String version : VERSIONS ) {
            if( greaterThanMaximum ) {
                greaterThanMaximum = version.equals(maximumVersion); // we already checked equivalence with the maximum
            }
            else {
                if( minimumVersion.equals(version) ) { // we already checked equivalence with the minimum
                    return false;
                }
                if( version.equals(currentVersion) ) {
                    return true;
                }
            }
        }
        return false;
    }

    static private Logger logger = vCloud.getLogger(vCloudMethod.class);
    static private Logger wire   = vCloud.getWireLogger(vCloudMethod.class);

    static public class Org {
        public String  token;
        public String  endpoint;
        public Version version;
        public Region region;
        public String url;
        public Iterable<VDC> vdcs;
    }

    static public class Version {
        public String loginUrl;
        public String version;

        public String toString() { return (version + " [" + loginUrl + "]"); }
    }

    static public class VDC {
        public DataCenter dataCenter;
        public HashMap<String,String> actions;
        public int vmQuota = -2;
        public int networkQuota = -2;
    }

    private vCloud provider;

    public vCloudMethod(@Nonnull vCloud provider) {
        this.provider = provider;
    }

    public @Nonnull Org authenticate(boolean force) throws CloudException, InternalException {
        Cache<Org> cache = Cache.getInstance(provider, "vCloudOrgs", Org.class, CacheLevel.CLOUD_ACCOUNT, new TimePeriod<Minute>(25, TimePeriod.MINUTE));
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was defined for this request");
        }
        String accountNumber = ctx.getAccountNumber();
        Iterable<Org> orgs = cache.get(ctx);
        Iterator<Org> it = ((force || orgs == null) ? null : orgs.iterator());

        if( it == null || !it.hasNext() ) {
            String endpoint = getVersion().loginUrl;

            if( wire.isDebugEnabled() ) {
                wire.debug("");
                wire.debug(">>> [POST (" + (new Date()) + ")] -> " + endpoint + " >--------------------------------------------------------------------------------------");
            }
            try {
                HttpClient client = getClient(true);
                HttpPost method =  new HttpPost(endpoint);
                Org org = new Org();

                org.version = getVersion();
                method.addHeader("Accept", "application/*+xml;version=" + org.version.version + ",application/*+xml;version=" + org.version.version);

                if( wire.isDebugEnabled() ) {
                    wire.debug(method.getRequestLine().toString());
                    for( Header header : method.getAllHeaders() ) {
                        wire.debug(header.getName() + ": " + header.getValue());
                    }
                    wire.debug("");
                }
                HttpResponse response;
                StatusLine status;

                try {
                    APITrace.trace(provider, "POST sessions");
                    response = client.execute(method);
                    if( wire.isDebugEnabled() ) {
                        wire.debug(response.getStatusLine().toString());
                        for( Header header : response.getAllHeaders() ) {
                            wire.debug(header.getName() + ": " + header.getValue());
                        }
                        wire.debug("");
                    }
                    status = response.getStatusLine();
                }
                catch( IOException e ) {
                    throw new CloudException(e);
                }
                if( status.getStatusCode() == HttpServletResponse.SC_OK ) {
                    org.token = response.getFirstHeader("x-vcloud-authorization").getValue();
                    if( org.token == null ) {
                        throw new CloudException(CloudErrorType.AUTHENTICATION, 200, "Token Empty", "No token was provided");
                    }
                    HttpEntity entity = response.getEntity();
                    String body;

                    try {
                        body = EntityUtils.toString(entity);
                        if( wire.isDebugEnabled() ) {
                            wire.debug(body);
                            wire.debug("");
                        }
                    }
                    catch( IOException e ) {
                        throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                    }
                    try {
                        ByteArrayInputStream bas = new ByteArrayInputStream(body.getBytes());

                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        DocumentBuilder parser = factory.newDocumentBuilder();
                        Document doc = parser.parse(bas);

                        bas.close();
                        if( matches(org.version.version, "1.5", null) ) {
                            NodeList orgNodes = doc.getElementsByTagName("Link");

                            for( int i=0; i<orgNodes.getLength(); i++ ) {
                                Node orgNode = orgNodes.item(i);

                                if( orgNode.hasAttributes() ) {
                                    Node type = orgNode.getAttributes().getNamedItem("type");

                                    if( type != null && type.getNodeValue().trim().equals(getMediaTypeForOrg()) ) {
                                        Node name = orgNode.getAttributes().getNamedItem("name");

                                        if( name != null && name.getNodeValue().trim().equals(accountNumber) ) {
                                            Node href = orgNode.getAttributes().getNamedItem("href");

                                            if( href != null ) {
                                                Region region = new Region();
                                                String url = href.getNodeValue().trim();

                                                region.setActive(true);
                                                region.setAvailable(true);
                                                if( provider.isCompat() ) {
                                                    region.setProviderRegionId("/org/" + url.substring(url.lastIndexOf('/') + 1));
                                                }
                                                else {
                                                    region.setProviderRegionId(url.substring(url.lastIndexOf('/') + 1));
                                                }
                                                region.setJurisdiction("US");
                                                region.setName(name.getNodeValue().trim());

                                                org.endpoint = url.substring(0, url.lastIndexOf("/api/org"));
                                                org.region = region;
                                                org.url = url;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else {
                            NodeList orgNodes = doc.getElementsByTagName("Org");

                            for( int i=0; i<orgNodes.getLength(); i++ ) {
                                Node orgNode = orgNodes.item(i);

                                if( orgNode.hasAttributes() ) {
                                    Node name = orgNode.getAttributes().getNamedItem("name");
                                    Node href = orgNode.getAttributes().getNamedItem("href");

                                    if( href != null ) {
                                        String url = href.getNodeValue().trim();
                                        Region region = new Region();

                                        if( !url.endsWith("/org/" + accountNumber) ) {
                                            continue;
                                        }
                                        region.setActive(true);
                                        region.setAvailable(true);
                                        if( provider.isCompat() ) {
                                            region.setProviderRegionId("/org/" + url.substring(url.lastIndexOf('/') + 1));
                                        }
                                        else {
                                            region.setProviderRegionId(url.substring(url.lastIndexOf('/') + 1));
                                        }
                                        region.setJurisdiction("US");
                                        region.setName(name == null ? accountNumber : name.getNodeValue().trim());
                                        org.endpoint = url.substring(0, url.lastIndexOf("/org/"));
                                        org.region = region;
                                        org.url = url;
                                    }
                                }
                            }
                        }
                    }
                    catch( IOException e ) {
                        throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                    }
                    catch( ParserConfigurationException e ) {
                        throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                    }
                    catch( SAXException e ) {
                        throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                    }
                }
                else {
                    HttpEntity entity = response.getEntity();

                    if( entity != null ) {
                        String body;

                        try {
                            body = EntityUtils.toString(entity);
                            if( wire.isDebugEnabled() ) {
                                wire.debug(body);
                                wire.debug("");
                            }
                        }
                        catch( IOException e ) {
                            throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                        }
                        vCloudException.Data data = vCloudException.parseException(status.getStatusCode(), body);

                        if( data == null ) {
                            throw new vCloudException(CloudErrorType.GENERAL, status.getStatusCode(), response.getStatusLine().getReasonPhrase(), "No further information");
                        }
                        logger.error("[" +  status.getStatusCode() + " : " + data.title + "] " + data.description);
                        throw new vCloudException(data);
                    }
                    throw new CloudException(CloudErrorType.AUTHENTICATION, status.getStatusCode(), status.getReasonPhrase(), "Authentication failed");
                }
                if( org.endpoint == null ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), "No Org", "No org was identified for " + ctx.getAccountNumber());
                }
                cache.put(ctx, Collections.singletonList(org));
                loadVDCs(org);
                return org;
            }
            finally {
                if( wire.isDebugEnabled() ) {
                    wire.debug("<<< [POST (" + (new Date()) + ")] -> " + endpoint + " <--------------------------------------------------------------------------------------");
                    wire.debug("");
                }
            }
        }
        else {
            return it.next();
        }
    }

    public void delete(@Nonnull String resource, @Nonnull String id) throws CloudException, InternalException {
        if( logger.isTraceEnabled() ) {
            logger.trace("ENTER: " + vCloudMethod.class.getName() + ".delete(" + resource + "," + id + ")");
        }
        try {
            Org org = authenticate(false);
            String endpoint = toURL(resource, id);

            if( wire.isDebugEnabled() ) {
                wire.debug("");
                wire.debug(">>> [DELETE (" + (new Date()) + ")] -> " + endpoint + " >--------------------------------------------------------------------------------------");
            }
            try {
                HttpClient client = getClient(false);
                HttpDelete delete = new HttpDelete(endpoint);

                delete.addHeader("Accept", "application/*+xml;version=" + org.version.version + ",application/*+xml;version=" + org.version.version);
                delete.addHeader("x-vcloud-authorization", org.token);

                if( wire.isDebugEnabled() ) {
                    wire.debug(delete.getRequestLine().toString());
                    for( Header header : delete.getAllHeaders() ) {
                        wire.debug(header.getName() + ": " + header.getValue());
                    }
                    wire.debug("");
                }
                HttpResponse response;

                try {
                    APITrace.trace(provider, "DELETE " + resource);
                    response = client.execute(delete);
                    if( wire.isDebugEnabled() ) {
                        wire.debug(response.getStatusLine().toString());
                        for( Header header : response.getAllHeaders() ) {
                            wire.debug(header.getName() + ": " + header.getValue());
                        }
                        wire.debug("");
                    }
                }
                catch( IOException e ) {
                    logger.error("I/O error from server communications: " + e.getMessage());
                    e.printStackTrace();
                    throw new InternalException(e);
                }
                int code = response.getStatusLine().getStatusCode();

                logger.debug("HTTP STATUS: " + code);

                if( code == HttpServletResponse.SC_UNAUTHORIZED ) {
                    authenticate(true);
                    delete(resource, id);
                }
                else if( code != HttpServletResponse.SC_NOT_FOUND && code != HttpServletResponse.SC_NO_CONTENT && code != HttpServletResponse.SC_OK && code != HttpServletResponse.SC_ACCEPTED ) {
                    logger.error("DELETE request got unexpected " + code);
                    String xml = null;

                    try {
                        HttpEntity entity = response.getEntity();

                        if( entity != null ) {
                            xml = EntityUtils.toString(entity);
                            if( wire.isDebugEnabled() ) {
                                wire.debug(xml);
                                wire.debug("");
                            }
                        }
                    }
                    catch( IOException e ) {
                        logger.error("Failed to read response error due to a cloud I/O error: " + e.getMessage());
                        e.printStackTrace();
                        throw new CloudException(e);
                    }

                    vCloudException.Data data = vCloudException.parseException(code, xml);

                    if( data == null ) {
                        throw new vCloudException(CloudErrorType.GENERAL, code, response.getStatusLine().getReasonPhrase(), "No further information");
                    }
                    logger.error("[" +  code + " : " + data.title + "] " + data.description);
                    throw new vCloudException(data);
                }
            }
            finally {
                if( wire.isDebugEnabled() ) {
                    wire.debug("<<< [DELETE (" + (new Date()) + ")] -> " + endpoint + " <--------------------------------------------------------------------------------------");
                    wire.debug("");
                }
            }
        }
        finally {
            if( logger.isTraceEnabled() ) {
                logger.trace("EXIT: " + vCloudMethod.class.getName() + ".delete()");
            }

        }
    }

    public @Nullable String get(@Nonnull String resource, @Nullable String id) throws CloudException, InternalException {
        if( logger.isTraceEnabled() ) {
            logger.trace("ENTER: " + vCloudMethod.class.getName() + ".get(" + resource + "," + id + ")");
        }
        try {
            Org org = authenticate(false);
            String endpoint = toURL(resource, id);

            if( wire.isDebugEnabled() ) {
                wire.debug("");
                wire.debug(">>> [GET (" + (new Date()) + ")] -> " + endpoint + " >--------------------------------------------------------------------------------------");
            }
            try {
                HttpClient client = getClient(false);
                HttpGet get = new HttpGet(endpoint);

                get.addHeader("Accept", "application/*+xml;version=" + org.version.version + ",application/*+xml;version=" + org.version.version);
                get.addHeader("x-vcloud-authorization", org.token);

                if( wire.isDebugEnabled() ) {
                    wire.debug(get.getRequestLine().toString());
                    for( Header header : get.getAllHeaders() ) {
                        wire.debug(header.getName() + ": " + header.getValue());
                    }
                    wire.debug("");
                }
                HttpResponse response;

                try {
                    APITrace.trace(provider, "GET " + resource);
                    response = client.execute(get);
                    if( wire.isDebugEnabled() ) {
                        wire.debug(response.getStatusLine().toString());
                        for( Header header : response.getAllHeaders() ) {
                            wire.debug(header.getName() + ": " + header.getValue());
                        }
                        wire.debug("");
                    }
                }
                catch( IOException e ) {
                    logger.error("I/O error from server communications: " + e.getMessage());
                    e.printStackTrace();
                    throw new InternalException(e);
                }
                int code = response.getStatusLine().getStatusCode();

                logger.debug("HTTP STATUS: " + code);

                if( code == HttpServletResponse.SC_NOT_FOUND ) {
                    return null;
                }
                else if( code == HttpServletResponse.SC_UNAUTHORIZED ) {
                    authenticate(true);
                    return get(resource, id);
                }
                else if( code == HttpServletResponse.SC_NO_CONTENT ) {
                    return "";
                }
                else if( code == HttpServletResponse.SC_OK ) {
                    String xml = null;

                    try {
                        HttpEntity entity = response.getEntity();

                        if( entity != null ) {
                            xml = EntityUtils.toString(entity);
                            if( wire.isDebugEnabled() ) {
                                wire.debug(xml);
                                wire.debug("");
                            }
                        }
                    }
                    catch( IOException e ) {
                        logger.error("Failed to read response error due to a cloud I/O error: " + e.getMessage());
                        e.printStackTrace();
                        throw new CloudException(e);
                    }
                    return xml;
                }
                else {
                    logger.error("Expected OK for GET request, got " + code);
                    String xml = null;

                    try {
                        HttpEntity entity = response.getEntity();

                        if( entity != null ) {
                            xml = EntityUtils.toString(entity);
                            if( wire.isDebugEnabled() ) {
                                wire.debug(xml);
                                wire.debug("");
                            }
                        }
                    }
                    catch( IOException e ) {
                        logger.error("Failed to read response error due to a cloud I/O error: " + e.getMessage());
                        e.printStackTrace();
                        throw new CloudException(e);
                    }

                    vCloudException.Data data = vCloudException.parseException(code, xml);

                    if( data == null ) {
                        throw new vCloudException(CloudErrorType.GENERAL, code, response.getStatusLine().getReasonPhrase(), "No further information");
                    }
                    logger.error("[" +  code + " : " + data.title + "] " + data.description);
                    throw new vCloudException(data);
                }
            }
            finally {
                if( wire.isDebugEnabled() ) {
                    wire.debug("<<< [GET (" + (new Date()) + ")] -> " + endpoint + " <--------------------------------------------------------------------------------------");
                    wire.debug("");
                }
            }
        }
        finally {
            if( logger.isTraceEnabled() ) {
                logger.trace("EXIT: " + vCloudMethod.class.getName() + ".get()");
            }

        }
    }

    public @Nonnull String getAction(@Nonnull String endpoint) {
        String[] parts = endpoint.split("/");

        return parts[parts.length-1];
    }

    protected @Nonnull HttpClient getClient(boolean forAuthentication) throws CloudException, InternalException {
        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was defined for this request");
        }
        String endpoint = ctx.getEndpoint();

        if( endpoint == null ) {
            throw new CloudException("No cloud endpoint was defined");
        }
        boolean ssl = endpoint.startsWith("https");
        int targetPort;
        URI uri;

        try {
            uri = new URI(endpoint);
            targetPort = uri.getPort();
            if( targetPort < 1 ) {
                targetPort = (ssl ? 443 : 80);
            }
        }
        catch( URISyntaxException e ) {
            throw new CloudException(e);
        }
        HttpHost targetHost = new HttpHost(uri.getHost(), targetPort, uri.getScheme());
        HttpParams params = new BasicHttpParams();

        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
        //noinspection deprecation
        HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);
        HttpProtocolParams.setUserAgent(params, "");

        Properties p = ctx.getCustomProperties();

        if( p != null ) {
            String proxyHost = p.getProperty("proxyHost");
            String proxyPort = p.getProperty("proxyPort");

            if( proxyHost != null ) {
                int port = 0;

                if( proxyPort != null && proxyPort.length() > 0 ) {
                    port = Integer.parseInt(proxyPort);
                }
                params.setParameter(ConnRoutePNames.DEFAULT_PROXY, new HttpHost(proxyHost, port, ssl ? "https" : "http"));
            }
        }
        DefaultHttpClient client = new DefaultHttpClient(params);

        if( provider.isInsecure() ) {
            try {
                client.getConnectionManager().getSchemeRegistry().register(new Scheme("https", 443, new SSLSocketFactory(new TrustStrategy() {

                    public boolean isTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                        return true;
                    }
                }, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)));
            }
            catch( Throwable t ) {
                t.printStackTrace();
            }
        }
        if( forAuthentication ) {
            try {
                String userName = new String(ctx.getAccessPublic(), "utf-8") + "@" + ctx.getAccountNumber();
                String password = new String(ctx.getAccessPrivate(), "utf-8");

                client.getCredentialsProvider().setCredentials(new AuthScope(targetHost.getHostName(), targetHost.getPort()), new UsernamePasswordCredentials(userName, password));
            }
            catch( UnsupportedEncodingException e ) {
                throw new InternalException(e);
            }
        }
        return client;
    }

    public @Nonnull String getMediaTypeForActionInstantiateVApp() {
        return "application/vnd.vmware.vcloud.instantiateVAppTemplateParams+xml";
    }

    public @Nonnull String getMediaTypeForActionDeployVApp() {
        return ">application/vnd.vmware.vcloud.deployVAppParams+xml";
    }

    public @Nonnull String getMediaTypeForActionUndeployVApp() {
        return ">application/vnd.vmware.vcloud.undeployVAppParams+xml";
    }

    public @Nonnull String getMediaTypeForCatalog() {
        return "application/vnd.vmware.vcloud.catalog+xml";
    }

    public @Nonnull String getMediaTypeForOrg() {
        return "application/vnd.vmware.vcloud.org+xml";
    }

    public @Nonnull String getMediaTypeForVApp() {
        return "application/vnd.vmware.vcloud.vApp+xml";
    }

    public @Nonnull String getMediaTypeForVDC() {
        return "application/vnd.vmware.vcloud.vdc+xml";
    }

    public int getNetworkQuota() throws CloudException, InternalException {
        int quota =-2;

        for( VDC vdc : authenticate(false).vdcs ) {
            int q = vdc.networkQuota;

            if( q > -1 ) {
                if( quota == -2 ) {
                    quota = q;
                }
                else {
                    quota += q;
                }
            }
        }
        return quota;
    }

    public @Nonnull String getOrgName(@Nonnull String href) throws CloudException, InternalException {
        String id = provider.toID(href);
        String xml = get("org", id);

        if( xml == null ) {
            return id;
        }
        NodeList orgs = parseXML(xml).getElementsByTagName("Org");

        if( orgs.getLength() < 1 ) {
            return id;
        }
        Node org = orgs.item(0);

        if( !org.hasAttributes() ) {
            return id;
        }

        Node name = org.getAttributes().getNamedItem("name");

        if( name == null ) {
            return id;
        }
        return name.getNodeValue().trim();
    }

    public @Nonnull Region getRegion() throws CloudException, InternalException {
        return authenticate(false).region;
    }

    private @Nonnull Version getVersion() throws CloudException, InternalException {
        Cache<Version> cache = Cache.getInstance(provider, "vCloudVersions", Version.class, CacheLevel.CLOUD, new TimePeriod<Day>(1, TimePeriod.DAY));

        ProviderContext ctx = provider.getContext();

        if( ctx == null ) {
            throw new CloudException("No context was defined for this request");
        }
        {
            Iterable<Version> versions = cache.get(ctx);


            Iterator<Version> it = (versions == null ? null : versions.iterator());

            if( it != null && it.hasNext() ) {
                return it.next();
            }
        }
        if( wire.isDebugEnabled() ) {
            wire.debug("");
            wire.debug(">>> [GET (" + (new Date()) + ")] -> " + ctx.getEndpoint() + " >--------------------------------------------------------------------------------------");
        }
        try {
            final String[] preferred = provider.getVersionPreference();
            HttpClient client = getClient(false);
            HttpGet method =  new HttpGet(ctx.getEndpoint() + "/api/versions");

            if( wire.isDebugEnabled() ) {
                wire.debug(method.getRequestLine().toString());
                for( Header header : method.getAllHeaders() ) {
                    wire.debug(header.getName() + ": " + header.getValue());
                }
                wire.debug("");
            }
            HttpResponse response;
            StatusLine status;

            try {
                APITrace.trace(provider, "GET versions");
                response = client.execute(method);
                if( wire.isDebugEnabled() ) {
                    wire.debug(response.getStatusLine().toString());
                    for( Header header : response.getAllHeaders() ) {
                        wire.debug(header.getName() + ": " + header.getValue());
                    }
                    wire.debug("");
                }
                status = response.getStatusLine();
            }
            catch( IOException e ) {
                throw new CloudException(e);
            }
            if( status.getStatusCode() == HttpServletResponse.SC_OK ) {
                HttpEntity entity = response.getEntity();
                String body;

                try {
                    body = EntityUtils.toString(entity);
                    if( wire.isDebugEnabled() ) {
                        wire.debug(body);
                        wire.debug("");
                    }
                }
                catch( IOException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
                try {
                    ByteArrayInputStream bas = new ByteArrayInputStream(body.getBytes());

                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder parser = factory.newDocumentBuilder();
                    Document doc = parser.parse(bas);

                    bas.close();

                    NodeList versions = doc.getElementsByTagName("VersionInfo");
                    TreeSet<Version> set = new TreeSet<Version>(new Comparator<Version>() {
                        public int compare(Version version1, Version version2) {
                            if( version1.equals(version2) ) {
                                return 0;
                            }
                            if( preferred != null ) {
                                for( String v : preferred ) {
                                    if( v.equals(version1.version) ) {
                                        return -1;
                                    }
                                    else if( v.equals(version2.version) ) {
                                        return 1;
                                    }
                                }
                            }
                            for( String v : VERSIONS ) {
                                if( v.equals(version1.version) ) {
                                    return -1;
                                }
                                else if( v.equals(version2.version) ) {
                                    return 1;
                                }
                            }
                            return -version1.version.compareTo(version2.version);
                        }
                    });
                    for( int i=0; i<versions.getLength(); i++ ) {
                        Node versionInfo = versions.item(i);
                        NodeList vattrs = versionInfo.getChildNodes();
                        String version = null;
                        String url = null;

                        for( int j=0; j<vattrs.getLength(); j++ ) {
                            Node attr = vattrs.item(j);

                            if( attr.getNodeName().equalsIgnoreCase("Version") && attr.hasChildNodes() ) {
                                version = attr.getFirstChild().getNodeValue().trim();
                            }
                            else if( attr.getNodeName().equalsIgnoreCase("LoginUrl") && attr.hasChildNodes() ) {
                                url = attr.getFirstChild().getNodeValue().trim();
                            }
                        }
                        if( version == null || url == null || !isSupported(version) ) {

                            continue;
                        }
                        Version v = new Version();
                        v.version = version;
                        v.loginUrl = url;
                        set.add(v);
                    }
                    if( set.isEmpty() ) {
                        throw new CloudException("Unable to identify a supported version");
                    }
                    Version v = set.iterator().next();

                    cache.put(ctx, set);
                    return v;
                }
                catch( IOException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
                catch( ParserConfigurationException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
                catch( SAXException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
            }
            else {
                logger.error("Expected OK for GET request, got " + status.getStatusCode());
                String xml = null;

                try {
                    HttpEntity entity = response.getEntity();

                    if( entity != null ) {
                        xml = EntityUtils.toString(entity);
                        if( wire.isDebugEnabled() ) {
                            wire.debug(xml);
                            wire.debug("");
                        }
                    }
                }
                catch( IOException e ) {
                    logger.error("Failed to read response error due to a cloud I/O error: " + e.getMessage());
                    e.printStackTrace();
                    throw new CloudException(e);
                }

                vCloudException.Data data = vCloudException.parseException(status.getStatusCode(), xml);

                if( data == null ) {
                    throw new vCloudException(CloudErrorType.GENERAL, status.getStatusCode(), response.getStatusLine().getReasonPhrase(), "No further information");
                }
                logger.error("[" +  status.getStatusCode() + " : " + data.title + "] " + data.description);
                throw new vCloudException(data);
            }
        }
        finally {
            if( wire.isDebugEnabled() ) {
                wire.debug("<<< [GET (" + (new Date()) + ")] -> " + ctx.getEndpoint() + " <--------------------------------------------------------------------------------------");
                wire.debug("");
            }
        }
    }

    public int getVMQuota() throws CloudException, InternalException {
        int quota =-2;

        for( VDC vdc : authenticate(false).vdcs ) {
            int q = vdc.vmQuota;

            if( q > -1 ) {
                if( quota == -2 ) {
                    quota = q;
                }
                else {
                    quota += q;
                }
            }
        }
        return quota;
    }

    public Collection<DataCenter> listDataCenters() throws CloudException, InternalException {
        ArrayList<DataCenter> dcs = new ArrayList<DataCenter>();

        for( VDC vdc : authenticate(false).vdcs ) {
            dcs.add(vdc.dataCenter);
        }
        return dcs;
    }

    private void loadVDC(@Nonnull VDC vdc, @Nonnull String id) throws CloudException, InternalException {
        String xml = get("vdc", id);

        if( xml != null ) {
            NodeList vdcs = parseXML(xml).getElementsByTagName("Vdc");

            if( vdcs.getLength() < 1 ) {
                return;
            }
            NodeList attributes = vdcs.item(0).getChildNodes();

            for( int i=0; i<attributes.getLength(); i++ ) {
                Node attribute = attributes.item(i);

                if( attribute.getNodeName().equalsIgnoreCase("Link") && attribute.hasAttributes() ) {
                    Node rel = attribute.getAttributes().getNamedItem("rel");

                    if( rel.getNodeValue().trim().equalsIgnoreCase("add") ) {
                        Node type = attribute.getAttributes().getNamedItem("type");
                        Node href = attribute.getAttributes().getNamedItem("href");

                        if( type != null && href != null ) {
                            vdc.actions.put(type.getNodeValue().trim(), href.getNodeValue().trim());
                        }
                    }
                }
                else if( attribute.getNodeName().equalsIgnoreCase("VmQuota") && attribute.hasChildNodes() ) {
                    try {
                        vdc.vmQuota = Integer.parseInt(attribute.getFirstChild().getNodeValue().trim());
                    }
                    catch( NumberFormatException ignore ) {
                        // ignore
                    }
                }
                else if( attribute.getNodeName().equalsIgnoreCase("NetworkQuota") && attribute.hasChildNodes() ) {
                    try {
                        vdc.networkQuota = Integer.parseInt(attribute.getFirstChild().getNodeValue().trim());
                    }
                    catch( NumberFormatException ignore ) {
                        // ignore
                    }
                }
                else if( attribute.getNodeName().equalsIgnoreCase("IsEnabled") && attribute.hasChildNodes() ) {
                    boolean enabled = attribute.getFirstChild().getNodeValue().trim().equalsIgnoreCase("true");

                    if( !enabled ) {
                        vdc.dataCenter.setActive(false);
                        vdc.dataCenter.setAvailable(false);
                    }
                }
            }
        }
    }

    private void loadVDCs(@Nonnull Org org) throws CloudException, InternalException {
        if( wire.isDebugEnabled() ) {
            wire.debug("");
            wire.debug(">>> [GET (" + (new Date()) + ")] -> " + org.url + " >--------------------------------------------------------------------------------------");
        }
        try {
            HttpClient client = getClient(false);
            HttpGet method =  new HttpGet(org.url);

            method.addHeader("Accept", "application/*+xml;version=" + org.version.version + ",application/*+xml;version=" + org.version.version);
            method.addHeader("x-vcloud-authorization", org.token);

            if( wire.isDebugEnabled() ) {
                wire.debug(method.getRequestLine().toString());
                for( Header header : method.getAllHeaders() ) {
                    wire.debug(header.getName() + ": " + header.getValue());
                }
                wire.debug("");
            }
            HttpResponse response;
            StatusLine status;

            try {
                APITrace.trace(provider, "GET org");
                response = client.execute(method);
                if( wire.isDebugEnabled() ) {
                    wire.debug(response.getStatusLine().toString());
                    for( Header header : response.getAllHeaders() ) {
                        wire.debug(header.getName() + ": " + header.getValue());
                    }
                    wire.debug("");
                }
                status = response.getStatusLine();
            }
            catch( IOException e ) {
                throw new CloudException(e);
            }
            if( status.getStatusCode() == HttpServletResponse.SC_OK ) {
                HttpEntity entity = response.getEntity();
                String body;

                try {
                    body = EntityUtils.toString(entity);
                    if( wire.isDebugEnabled() ) {
                        wire.debug(body);
                        wire.debug("");
                    }
                }
                catch( IOException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
                try {
                    ByteArrayInputStream bas = new ByteArrayInputStream(body.getBytes());

                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder parser = factory.newDocumentBuilder();
                    ArrayList<VDC> vdcs = new ArrayList<VDC>();
                    Document doc = parser.parse(bas);
                    bas.close();

                    NodeList links = doc.getElementsByTagName("Link");

                    for( int i=0; i<links.getLength(); i++ ) {
                        Node link = links.item(i);

                        if( link.hasAttributes() ) {
                            Node type = link.getAttributes().getNamedItem("type");

                            if( type != null && type.getNodeValue().trim().equals("application/vnd.vmware.vcloud.vdc+xml") ) {
                                Node name = link.getAttributes().getNamedItem("name");

                                if( name != null ) {
                                    DataCenter dc = new DataCenter();
                                    VDC vdc = new VDC();

                                    vdc.actions = new HashMap<String, String>();
                                    dc.setActive(true);
                                    dc.setAvailable(true);
                                    dc.setName(name.getNodeValue().trim());
                                    dc.setRegionId(org.region.getProviderRegionId());
                                    Node href = link.getAttributes().getNamedItem("href");

                                    if( href != null ) {
                                        String id = provider.toID(href.getNodeValue().trim());

                                        dc.setProviderDataCenterId(id);
                                        vdc.dataCenter = dc;
                                        loadVDC(vdc, id);
                                        vdcs.add(vdc);
                                    }
                                }
                            }
                        }
                    }
                    org.vdcs = vdcs;
                }
                catch( IOException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
                catch( ParserConfigurationException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
                catch( SAXException e ) {
                    throw new CloudException(CloudErrorType.GENERAL, status.getStatusCode(), status.getReasonPhrase(), e.getMessage());
                }
            }
            else {
                logger.error("Expected OK for GET request, got " + status.getStatusCode());
                String xml = null;

                try {
                    HttpEntity entity = response.getEntity();

                    if( entity != null ) {
                        xml = EntityUtils.toString(entity);
                        if( wire.isDebugEnabled() ) {
                            wire.debug(xml);
                            wire.debug("");
                        }
                    }
                }
                catch( IOException e ) {
                    logger.error("Failed to read response error due to a cloud I/O error: " + e.getMessage());
                    e.printStackTrace();
                    throw new CloudException(e);
                }

                vCloudException.Data data = vCloudException.parseException(status.getStatusCode(), xml);

                if( data == null ) {
                    throw new vCloudException(CloudErrorType.GENERAL, status.getStatusCode(), response.getStatusLine().getReasonPhrase(), "No further information");
                }
                logger.error("[" +  status.getStatusCode() + " : " + data.title + "] " + data.description);
                throw new vCloudException(data);
            }
        }
        finally {
            if( wire.isDebugEnabled() ) {
                wire.debug("<<< [GET (" + (new Date()) + ")] -> " + org.url + " <--------------------------------------------------------------------------------------");
                wire.debug("");
            }
        }
    }

    public void parseError(@Nonnull Node errorNode) throws CloudException {
        NodeList attributes = errorNode.getChildNodes();
        CloudErrorType type = CloudErrorType.GENERAL;
        String message = "Unknown";
        String major = "";
        String minor = "";

        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attr = attributes.item(i);

            if( attr.getNodeName().equalsIgnoreCase("message") && attr.hasChildNodes() ) {
                message = attr.getFirstChild().getNodeValue().trim();
            }
            else if( attr.getNodeName().equalsIgnoreCase("majorErrorCode") && attr.hasChildNodes() ) {
                major = attr.getFirstChild().getNodeValue().trim();
            }
            else if( attr.getNodeName().equalsIgnoreCase("minorErrorCode") && attr.hasChildNodes() ) {
                minor = attr.getFirstChild().getNodeValue().trim();
            }
        }
        throw new CloudException(type, 200, major + ":" + minor, message);
    }

    public @Nonnull Document parseXML(@Nonnull String xml) throws CloudException, InternalException {
        try {
            ByteArrayInputStream bas = new ByteArrayInputStream(xml.getBytes());

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder parser = factory.newDocumentBuilder();

            return parser.parse(bas);
        }
        catch( ParserConfigurationException e ) {
            throw new InternalException(e);
        }
        catch( SAXException e ) {
            throw new CloudException(e);
        }
        catch( IOException e ) {
            throw new InternalException(e);
        }
    }

    public @Nonnull String post(@Nonnull String action, @Nullable String vdcId, @Nullable String payload) throws CloudException, InternalException {
        if( logger.isTraceEnabled() ) {
            logger.trace("ENTER: " + vCloudMethod.class.getName() + ".post(" + action + ")");
        }
        try {
            Org org = authenticate(false);
            String endpoint = null;
            VDC vdc = null;

            for( VDC v : org.vdcs ) {
                if( vdcId == null ) {
                    vdc = v;
                    break;
                }
                else if( v.dataCenter.getProviderDataCenterId().equals(vdcId) ) {
                    vdc = v;
                    break;
                }
                else if( vdc == null ) {
                    vdc = v;
                }
            }
            if( vdc == null ) {
                throw new CloudException("No VDC was identified for this request (requested " + vdcId + ")");
            }
            String contentType;

            if( action.equals(INSTANTIATE_VAPP) ) {
                contentType = getMediaTypeForActionInstantiateVApp();
                endpoint = vdc.actions.get(contentType);
            }
            if( endpoint == null) {
                throw new CloudException("No endpoint for " + action);
            }
            return post(action, endpoint, payload);
        }
        finally {
            if( logger.isTraceEnabled() ) {
                logger.trace("EXIT: " + vCloudMethod.class.getName() + ".post()");
            }
        }
    }

    public @Nonnull String post(@Nonnull String action, @Nonnull String endpoint, @Nullable String contentType, @Nullable String payload) throws CloudException, InternalException {
        if( logger.isTraceEnabled() ) {
            logger.trace("ENTER: " + vCloudMethod.class.getName() + ".post(" + endpoint + ")");
        }
        try {
            if( wire.isDebugEnabled() ) {
                wire.debug("");
                wire.debug(">>> [POST (" + (new Date()) + ")] -> " + endpoint + " >--------------------------------------------------------------------------------------");
            }
            try {
                Org org = authenticate(false);
                HttpClient client = getClient(false);
                HttpPost post = new HttpPost(endpoint);

                post.addHeader("Accept", "application/*+xml;version=" + org.version.version + ",application/*+xml;version=" + org.version.version);
                post.addHeader("x-vcloud-authorization", org.token);
                if( contentType != null ) {
                    post.addHeader("ContentType", contentType);
                }

                if( wire.isDebugEnabled() ) {
                    wire.debug(post.getRequestLine().toString());
                    for( Header header : post.getAllHeaders() ) {
                        wire.debug(header.getName() + ": " + header.getValue());
                    }
                    wire.debug("");
                }
                if( payload != null ) {
                    try {
                        //noinspection deprecation
                        post.setEntity(new StringEntity(payload == null ? "" : payload, "application/json", "UTF-8"));
                    }
                    catch( UnsupportedEncodingException e ) {
                        throw new InternalException(e);
                    }
                    try { wire.debug(EntityUtils.toString(post.getEntity())); }
                    catch( IOException ignore ) { }

                    wire.debug("");
                }
                HttpResponse response;

                try {
                    APITrace.trace(provider, "POST " + action);
                    response = client.execute(post);
                    if( wire.isDebugEnabled() ) {
                        wire.debug(response.getStatusLine().toString());
                        for( Header header : response.getAllHeaders() ) {
                            wire.debug(header.getName() + ": " + header.getValue());
                        }
                        wire.debug("");
                    }
                }
                catch( IOException e ) {
                    logger.error("I/O error from server communications: " + e.getMessage());
                    e.printStackTrace();
                    throw new InternalException(e);
                }
                int code = response.getStatusLine().getStatusCode();

                logger.debug("HTTP STATUS: " + code);

                if( code == HttpServletResponse.SC_NOT_FOUND ) {
                    throw new CloudException("No action match for " + endpoint);
                }
                else if( code == HttpServletResponse.SC_UNAUTHORIZED ) {
                    authenticate(true);
                    return post(action, endpoint, contentType, payload);
                }
                else if( code == HttpServletResponse.SC_NO_CONTENT ) {
                    return "";
                }
                else if( code == HttpServletResponse.SC_OK || code == HttpServletResponse.SC_CREATED || code == HttpServletResponse.SC_ACCEPTED ) {
                    String xml = null;

                    try {
                        HttpEntity entity = response.getEntity();

                        if( entity != null ) {
                            xml = EntityUtils.toString(entity);
                            if( wire.isDebugEnabled() ) {
                                wire.debug(xml);
                                wire.debug("");
                            }
                        }
                    }
                    catch( IOException e ) {
                        logger.error("Failed to read response error due to a cloud I/O error: " + e.getMessage());
                        e.printStackTrace();
                        throw new CloudException(e);
                    }
                    return xml;
                }
                else {
                    logger.error("Expected OK or CREATED or NO_CONTENT or ACCEPTED for POST request, got " + code);
                    String xml = null;

                    try {
                        HttpEntity entity = response.getEntity();

                        if( entity != null ) {
                            xml = EntityUtils.toString(entity);
                            if( wire.isDebugEnabled() ) {
                                wire.debug(xml);
                                wire.debug("");
                            }
                        }
                    }
                    catch( IOException e ) {
                        logger.error("Failed to read response error due to a cloud I/O error: " + e.getMessage());
                        e.printStackTrace();
                        throw new CloudException(e);
                    }

                    vCloudException.Data data = vCloudException.parseException(code, xml);

                    if( data == null ) {
                        throw new vCloudException(CloudErrorType.GENERAL, code, response.getStatusLine().getReasonPhrase(), "No further information");
                    }
                    logger.error("[" +  code + " : " + data.title + "] " + data.description);
                    throw new vCloudException(data);
                }
            }
            finally {
                if( wire.isDebugEnabled() ) {
                    wire.debug("<<< [POST (" + (new Date()) + ")] -> " + endpoint + " <--------------------------------------------------------------------------------------");
                    wire.debug("");
                }
            }
        }
        finally {
            if( logger.isTraceEnabled() ) {
                logger.trace("EXIT: " + vCloudMethod.class.getName() + ".post()");
            }
        }
    }

    public @Nonnull String toURL(@Nonnull String resource, @Nullable String id) throws CloudException, InternalException {
        Org org = authenticate(false);
        String url;

        if( id == null ) {
            if( matches(org.version.version, "1.5", null) ) {
                url = org.endpoint + "/api/" + resource;
            }
            else {
                url = org.endpoint + "/api/v" + org.version.version + "/" + resource;
            }
        }
        else {
            String r = (provider.isCompat() ? id : ("/" + resource + "/" + id));

            if( matches(org.version.version, "1.5", null) ) {
                url = org.endpoint + "/api" + r;
            }
            else {
                url = org.endpoint + "/api/v" + org.version.version + r;
            }
        }
        return url;
    }

    public void waitFor(@Nullable String xmlTask) throws CloudException {
        long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 30L);
        String taskId = null;

        while( timeout > System.currentTimeMillis() ) {
            if( xmlTask == null || xmlTask.equals("") ) {
                return;
            }
            NodeList tasks;

            try {
                tasks = parseXML(xmlTask).getElementsByTagName("Task");
            }
            catch( Throwable ignore ) {
                return;
            }
            if( tasks.getLength() < 1 ) {
                return;
            }
            Node task = tasks.item(0);

            if( task.hasAttributes() ) {
                Node status = task.getAttributes().getNamedItem("status");

                if( status != null ) {
                    String s = status.getNodeValue().trim();

                    if( s.equals("success") ) {
                        return;
                    }
                    else if( s.equals("error") ) {
                        NodeList elements = task.getChildNodes();

                        for( int i=0; i<elements.getLength(); i++ ) {
                            Node element = elements.item(i);

                            if( element.getNodeName().equalsIgnoreCase("Error") ) {
                                parseError(element);
                                return;
                            }
                        }
                    }
                }
                if( taskId == null ) {
                    Node href = task.getAttributes().getNamedItem("href");

                    if( href == null ) {
                        return;
                    }
                    taskId = provider.toID(href.getNodeValue().trim());
                }
            }
            try { Thread.sleep(15000L); }
            catch( InterruptedException ignore ) { }
            try { xmlTask = get("task", taskId); }
            catch( Throwable ignore ) { }
        }
        logger.warn("Task timed out: " + taskId);
    }
}