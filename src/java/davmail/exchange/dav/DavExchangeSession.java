/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2010  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.exchange.dav;

import davmail.BundleMessage;
import davmail.exception.DavMailAuthenticationException;
import davmail.exception.DavMailException;
import davmail.exchange.ExchangeSession;
import davmail.http.DavGatewayHttpClientFacade;
import davmail.ui.tray.DavGatewayTray;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.client.methods.PropFindMethod;
import org.apache.jackrabbit.webdav.property.DavProperty;
import org.apache.jackrabbit.webdav.property.DavPropertyName;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;
import org.apache.jackrabbit.webdav.property.DavPropertySet;
import org.apache.jackrabbit.webdav.xml.Namespace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;

/**
 * Webdav Exchange adapter.
 * Compatible with Exchange 2003 and 2007 with webdav available.
 */
public class DavExchangeSession extends ExchangeSession {
    protected static final DavPropertyNameSet WELL_KNOWN_FOLDERS = new DavPropertyNameSet();

    static {
        WELL_KNOWN_FOLDERS.add(Field.get("inbox").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("deleteditems").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("sentitems").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("sendmsg").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("drafts").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("calendar").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("contacts").davPropertyName);
        WELL_KNOWN_FOLDERS.add(Field.get("outbox").davPropertyName);
    }

    /**
     * @inheritDoc
     */
    public DavExchangeSession(String url, String userName, String password) throws IOException {
        super(url, userName, password);
    }

    @Override
    protected void buildSessionInfo(HttpMethod method) throws DavMailException {
        buildMailPath(method);

        // get base http mailbox http urls
        getWellKnownFolders();
    }

    static final String BASE_HREF = "<base href=\"";

    protected void buildMailPath(HttpMethod method) throws DavMailAuthenticationException {
        // find base url
        String line;

        // get user mail URL from html body (multi frame)
        BufferedReader mainPageReader = null;
        try {
            mainPageReader = new BufferedReader(new InputStreamReader(method.getResponseBodyAsStream()));
            //noinspection StatementWithEmptyBody
            while ((line = mainPageReader.readLine()) != null && line.toLowerCase().indexOf(BASE_HREF) == -1) {
            }
            if (line != null) {
                int start = line.toLowerCase().indexOf(BASE_HREF) + BASE_HREF.length();
                int end = line.indexOf('\"', start);
                String mailBoxBaseHref = line.substring(start, end);
                URL baseURL = new URL(mailBoxBaseHref);
                mailPath = baseURL.getPath();
                LOGGER.debug("Base href found in body, mailPath is " + mailPath);
                buildEmail(method.getURI().getHost());
                LOGGER.debug("Current user email is " + email);
            } else {
                // failover for Exchange 2007 : build standard mailbox link with email
                buildEmail(method.getURI().getHost());
                mailPath = "/exchange/" + email + '/';
                LOGGER.debug("Current user email is " + email + ", mailPath is " + mailPath);
            }
        } catch (IOException e) {
            LOGGER.error("Error parsing main page at " + method.getPath(), e);
        } finally {
            if (mainPageReader != null) {
                try {
                    mainPageReader.close();
                } catch (IOException e) {
                    LOGGER.error("Error parsing main page at " + method.getPath());
                }
            }
            method.releaseConnection();
        }


        if (mailPath == null || email == null) {
            throw new DavMailAuthenticationException("EXCEPTION_AUTHENTICATION_FAILED_PASSWORD_EXPIRED");
        }
    }

    protected String getURIPropertyIfExists(DavPropertySet properties, String alias) throws URIException {
        DavProperty property = properties.get(Field.get(alias).davPropertyName);
        if (property == null) {
            return null;
        } else {
            return URIUtil.decode((String) property.getValue());
        }
    }

    protected void getWellKnownFolders() throws DavMailException {
        // Retrieve well known URLs
        MultiStatusResponse[] responses;
        try {
            responses = DavGatewayHttpClientFacade.executePropFindMethod(
                    httpClient, URIUtil.encodePath(mailPath), 0, WELL_KNOWN_FOLDERS);
            if (responses.length == 0) {
                throw new DavMailException("EXCEPTION_UNABLE_TO_GET_MAIL_FOLDER", mailPath);
            }
            DavPropertySet properties = responses[0].getProperties(HttpStatus.SC_OK);
            inboxUrl = getURIPropertyIfExists(properties, "inbox");
            deleteditemsUrl = getURIPropertyIfExists(properties, "deleteditems");
            sentitemsUrl = getURIPropertyIfExists(properties, "sentitems");
            sendmsgUrl = getURIPropertyIfExists(properties, "sendmsg");
            draftsUrl = getURIPropertyIfExists(properties, "drafts");
            calendarUrl = getURIPropertyIfExists(properties, "calendar");
            contactsUrl = getURIPropertyIfExists(properties, "contacts");
            outboxUrl = getURIPropertyIfExists(properties, "outbox");
            // junk folder not available over webdav

            // default public folder path
            publicFolderUrl = PUBLIC_ROOT;

            // check public folder access
            try {
                if (inboxUrl != null) {
                    // try to build full public URI from inboxUrl
                    URI publicUri = new URI(inboxUrl, false);
                    publicUri.setPath(PUBLIC_ROOT);
                    publicFolderUrl = publicUri.getURI();
                }
                PropFindMethod propFindMethod = new PropFindMethod(publicFolderUrl, CONTENT_TAG, 0);
                try {
                    DavGatewayHttpClientFacade.executeMethod(httpClient, propFindMethod);
                } catch (IOException e) {
                    // workaround for NTLM authentication only on /public
                    if (!DavGatewayHttpClientFacade.hasNTLM(httpClient)) {
                        DavGatewayHttpClientFacade.addNTLM(httpClient);
                        DavGatewayHttpClientFacade.executeMethod(httpClient, propFindMethod);
                    }
                }
                // update public folder URI
                publicFolderUrl = propFindMethod.getURI().getURI();
            } catch (IOException e) {
                LOGGER.warn("Public folders not available: " + (e.getMessage() == null ? e : e.getMessage()));
                publicFolderUrl = "/public";
            }

            LOGGER.debug("Inbox URL: " + inboxUrl +
                    " Trash URL: " + deleteditemsUrl +
                    " Sent URL: " + sentitemsUrl +
                    " Send URL: " + sendmsgUrl +
                    " Drafts URL: " + draftsUrl +
                    " Calendar URL: " + calendarUrl +
                    " Contacts URL: " + contactsUrl +
                    " Outbox URL: " + outboxUrl +
                    " Public folder URL: " + publicFolderUrl
            );
        } catch (IOException e) {
            LOGGER.error(e.getMessage());
            throw new DavMailAuthenticationException("EXCEPTION_UNABLE_TO_GET_MAIL_FOLDER", mailPath);
        }
    }

    protected static class MultiCondition extends ExchangeSession.MultiCondition {
        protected MultiCondition(Operator operator, Condition... condition) {
            super(operator, condition);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            boolean first = true;
            buffer.append('(');
            for (Condition condition : conditions) {
                if (first) {
                    first = false;
                } else {
                    buffer.append(' ').append(operator).append(' ');
                }
                condition.appendTo(buffer);
            }
            buffer.append(')');
        }
    }

    protected static class NotCondition extends ExchangeSession.NotCondition {
        protected NotCondition(Condition condition) {
            super(condition);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            boolean first = true;
            buffer.append("( Not ");
            condition.appendTo(buffer);
            buffer.append(')');
        }
    }

    static final Map<Operator, String> operatorMap = new HashMap<Operator, String>();

    static {
        operatorMap.put(Operator.IsEqualTo, " = ");
        operatorMap.put(Operator.IsGreaterThanOrEqualTo, " >= ");
        operatorMap.put(Operator.IsGreaterThan, " > ");
        operatorMap.put(Operator.IsLessThan, " < ");
        operatorMap.put(Operator.Like, " like ");
        operatorMap.put(Operator.IsNull, " is null");

    }

    protected static class AttributeCondition extends ExchangeSession.AttributeCondition {
        protected AttributeCondition(String attributeName, Operator operator, String value) {
            super(attributeName, operator, value);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            buffer.append('"').append(Field.get(attributeName).getUri()).append('"');
            buffer.append(operatorMap.get(operator));
            buffer.append('\'');
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append(value);
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append('\'');
        }
    }

    protected static class HeaderCondition extends AttributeCondition {

        protected HeaderCondition(String attributeName, Operator operator, String value) {
            super(attributeName, operator, value);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            buffer.append('"').append(Field.getHeader(attributeName)).append('"');
            buffer.append(operatorMap.get(operator));
            buffer.append('\'');
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append(value);
            if (Operator.Like == operator) {
                buffer.append('%');
            }
            buffer.append('\'');
        }
    }

    protected static class MonoCondition extends ExchangeSession.MonoCondition {
        protected MonoCondition(String attributeName, Operator operator) {
            super(attributeName, operator);
        }

        @Override
        public void appendTo(StringBuilder buffer) {
            buffer.append('"').append(Field.get(attributeName).getUri()).append('"');
            buffer.append(operatorMap.get(operator));
        }
    }

    @Override
    public MultiCondition and(Condition... condition) {
        return new MultiCondition(Operator.And, condition);
    }

    @Override
    public MultiCondition or(Condition... condition) {
        return new MultiCondition(Operator.Or, condition);
    }

    @Override
    public Condition not(Condition condition) {
        if (condition == null) {
            return null;
        } else {
            return new NotCondition(condition);
        }
    }

    @Override
    public Condition equals(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsEqualTo, value);
    }

    @Override
    public Condition headerEquals(String headerName, String value) {
        return new HeaderCondition(headerName, Operator.IsEqualTo, value);
    }

    @Override
    public Condition gte(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsGreaterThanOrEqualTo, value);
    }

    @Override
    public Condition lt(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsLessThan, value);
    }

    @Override
    public Condition gt(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.IsGreaterThan, value);
    }

    @Override
    public Condition like(String attributeName, String value) {
        return new AttributeCondition(attributeName, Operator.Like, value);
    }

    @Override
    public Condition isNull(String attributeName) {
        return new MonoCondition(attributeName, Operator.IsNull);
    }

    @Override
    public Condition isTrue(String attributeName) {
        return new MonoCondition(attributeName, Operator.IsTrue);
    }

    @Override
    public Condition isFalse(String attributeName) {
        return new MonoCondition(attributeName, Operator.IsFalse);
    }


    protected Folder buildFolder(MultiStatusResponse entity) throws IOException {
        String href = URIUtil.decode(entity.getHref());
        Folder folder = new Folder();
        DavPropertySet properties = entity.getProperties(HttpStatus.SC_OK);
        folder.folderClass = getPropertyIfExists(properties, "outlookfolderclass", SCHEMAS_EXCHANGE);
        folder.hasChildren = "1".equals(getPropertyIfExists(properties, "hassubs", DAV));
        folder.noInferiors = "1".equals(getPropertyIfExists(properties, "nosubs", DAV));
        folder.unreadCount = getIntPropertyIfExists(properties, "unreadcount", URN_SCHEMAS_HTTPMAIL);
        folder.ctag = getPropertyIfExists(properties, "contenttag", Namespace.getNamespace("http://schemas.microsoft.com/repl/"));
        folder.etag = getPropertyIfExists(properties, "x30080040", SCHEMAS_MAPI_PROPTAG);

        // replace well known folder names
        if (href.startsWith(inboxUrl)) {
            folder.folderPath = href.replaceFirst(inboxUrl, INBOX);
        } else if (href.startsWith(sentitemsUrl)) {
            folder.folderPath = href.replaceFirst(sentitemsUrl, SENT);
        } else if (href.startsWith(draftsUrl)) {
            folder.folderPath = href.replaceFirst(draftsUrl, DRAFTS);
        } else if (href.startsWith(deleteditemsUrl)) {
            folder.folderPath = href.replaceFirst(deleteditemsUrl, TRASH);
        } else if (href.startsWith(calendarUrl)) {
            folder.folderPath = href.replaceFirst(calendarUrl, CALENDAR);
        } else if (href.startsWith(contactsUrl)) {
            folder.folderPath = href.replaceFirst(contactsUrl, CONTACTS);
        } else {
            int index = href.indexOf(mailPath.substring(0, mailPath.length() - 1));
            if (index >= 0) {
                if (index + mailPath.length() > href.length()) {
                    folder.folderPath = "";
                } else {
                    folder.folderPath = href.substring(index + mailPath.length());
                }
            } else {
                try {
                    URI folderURI = new URI(href, false);
                    folder.folderPath = folderURI.getPath();
                } catch (URIException e) {
                    throw new DavMailException("EXCEPTION_INVALID_FOLDER_URL", href);
                }
            }
        }
        if (folder.folderPath.endsWith("/")) {
            folder.folderPath = folder.folderPath.substring(0, folder.folderPath.length() - 1);
        }
        return folder;
    }

    /**
     * @inheritDoc
     */
    @Override
    public Folder getFolder(String folderName) throws IOException {
        MultiStatusResponse[] responses = DavGatewayHttpClientFacade.executePropFindMethod(
                httpClient, URIUtil.encodePath(getFolderPath(folderName)), 0, FOLDER_PROPERTIES);
        Folder folder = null;
        if (responses.length > 0) {
            folder = buildFolder(responses[0]);
            folder.folderName = folderName;
        }
        return folder;
    }

    /**
     * @inheritDoc
     */
    @Override
    public List<Folder> getSubFolders(String folderName, Condition condition, boolean recursive) throws IOException {
        boolean isPublic = folderName.startsWith("/public");
        String mode = (!isPublic && recursive) ? "DEEP" : "SHALLOW";
        List<Folder> folders = new ArrayList<Folder>();
        StringBuilder searchRequest = new StringBuilder();
        searchRequest.append("Select \"DAV:nosubs\", \"DAV:hassubs\", \"http://schemas.microsoft.com/exchange/outlookfolderclass\", " +
                "\"http://schemas.microsoft.com/repl/contenttag\", \"http://schemas.microsoft.com/mapi/proptag/x30080040\", " +
                "\"urn:schemas:httpmail:unreadcount\" FROM Scope('").append(mode).append(" TRAVERSAL OF \"").append(getFolderPath(folderName)).append("\"')\n" +
                " WHERE \"DAV:ishidden\" = False AND \"DAV:isfolder\" = True \n");
        if (condition != null) {
            searchRequest.append(" AND ");
            condition.appendTo(searchRequest);
        }
        MultiStatusResponse[] responses = DavGatewayHttpClientFacade.executeSearchMethod(
                httpClient, URIUtil.encodePath(getFolderPath(folderName)), searchRequest.toString());

        for (MultiStatusResponse response : responses) {
            Folder folder = buildFolder(response);
            folders.add(buildFolder(response));
            if (isPublic && recursive) {
                getSubFolders(folder.folderPath, condition, recursive);
            }
        }
        return folders;
    }

    protected String getPropertyIfExists(DavPropertySet properties, String name) {
        DavProperty property = properties.get(name, EMPTY);
        if (property == null) {
            return null;
        } else {
            return (String) property.getValue();
        }
    }

    protected int getIntPropertyIfExists(DavPropertySet properties, String name) {
        DavProperty property = properties.get(name, EMPTY);
        if (property == null) {
            return 0;
        } else {
            return Integer.parseInt((String) property.getValue());
        }
    }

    protected long getLongPropertyIfExists(DavPropertySet properties, String name) {
        DavProperty property = properties.get(name, EMPTY);
        if (property == null) {
            return 0;
        } else {
            return Long.parseLong((String) property.getValue());
        }
    }


    protected Message buildMessage(MultiStatusResponse responseEntity) throws URIException {
        Message message = new Message();
        message.messageUrl = URIUtil.decode(responseEntity.getHref());
        DavPropertySet properties = responseEntity.getProperties(HttpStatus.SC_OK);

        message.permanentUrl = getPropertyIfExists(properties, "permanenturl");
        message.size = getIntPropertyIfExists(properties, "messageSize");
        message.uid = getPropertyIfExists(properties, "uid");
        message.imapUid = getLongPropertyIfExists(properties, "imapUid");
        message.read = "1".equals(getPropertyIfExists(properties, "read"));
        message.junk = "1".equals(getPropertyIfExists(properties, "junk"));
        message.flagged = "2".equals(getPropertyIfExists(properties, "flagStatus"));
        message.draft = "9".equals(getPropertyIfExists(properties, "messageFlags"));
        String lastVerbExecuted = getPropertyIfExists(properties, "lastVerbExecuted");
        message.answered = "102".equals(lastVerbExecuted) || "103".equals(lastVerbExecuted);
        message.forwarded = "104".equals(lastVerbExecuted);
        message.date = getPropertyIfExists(properties, "date");
        message.deleted = "1".equals(getPropertyIfExists(properties, "deleted"));

        if (LOGGER.isDebugEnabled()) {
            StringBuilder buffer = new StringBuilder();
            buffer.append("Message");
            if (message.imapUid != 0) {
                buffer.append(" IMAP uid: ").append(message.imapUid);
            }
            if (message.uid != null) {
                buffer.append(" uid: ").append(message.uid);
            }
            buffer.append(" href: ").append(responseEntity.getHref()).append(" permanenturl:").append(message.permanentUrl);
            LOGGER.debug(buffer.toString());
        }
        return message;
    }

    @Override
    public MessageList searchMessages(String folderName, List<String> attributes, Condition condition) throws IOException {
        String folderUrl = getFolderPath(folderName);
        MessageList messages = new MessageList();
        StringBuilder searchRequest = new StringBuilder();
        searchRequest.append("Select \"http://schemas.microsoft.com/exchange/permanenturl\" as permanenturl");
        if (attributes != null) {
            for (String attribute : attributes) {
                Field field = Field.get(attribute);
                searchRequest.append(",\"").append(field.getUri()).append("\" as ").append(field.getAlias());
            }
        }
        searchRequest.append("                FROM Scope('SHALLOW TRAVERSAL OF \"").append(folderUrl).append("\"')\n")
                .append("                WHERE \"DAV:ishidden\" = False AND \"DAV:isfolder\" = False\n");
        if (condition != null) {
            searchRequest.append(" AND ");
            condition.appendTo(searchRequest);
        }
        // TODO order by ImapUid
        //searchRequest.append("       ORDER BY \"urn:schemas:httpmail:date\" ASC");
        DavGatewayTray.debug(new BundleMessage("LOG_SEARCH_QUERY", searchRequest));
        MultiStatusResponse[] responses = DavGatewayHttpClientFacade.executeSearchMethod(
                httpClient, URIUtil.encodePath(folderUrl), searchRequest.toString());

        for (MultiStatusResponse response : responses) {
            Message message = buildMessage(response);
            message.messageList = messages;
            messages.add(message);
        }
        Collections.sort(messages);
        return messages;
    }
}
