/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mastfrog.acteur.wicket;

import com.google.common.collect.Maps;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.wicket.adapters.RequestAdapter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.inject.Inject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.wicket.Application;
import org.apache.wicket.Session;
import org.apache.wicket.core.request.ClientInfo;
import org.apache.wicket.markup.MarkupParser;
import org.apache.wicket.protocol.http.IRequestLogger;
import org.apache.wicket.protocol.http.WebApplication;
import org.apache.wicket.protocol.http.request.WebClientInfo;
import org.apache.wicket.request.Request;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.request.http.WebRequest;
import org.apache.wicket.session.ISessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link ISessionStore} that works with web applications and
 * provides some specific http servlet/ session related functionality.
 *
 * @author jcompagner
 * @author Eelco Hillenius
 * @author Matej Knopp
 * @author Tim Boudreau
 */
@Singleton
public class ActeurSessionStore implements ISessionStore {

    public static final String COOKIE_NAME = "jsessionid";

    /**
     * log.
     */
    private static final Logger log = LoggerFactory.getLogger(ActeurSessionStore.class);

    /**
     *
     */
    private final Set<UnboundListener> unboundListeners = new CopyOnWriteArraySet<UnboundListener>();

    private final Set<BindListener> bindListeners = new CopyOnWriteArraySet<BindListener>();

    private final Map<String, SessionImpl> sessions = Maps.newConcurrentMap();
    private final Provider<SessionId> sessionId;

    /**
     * Construct.
     */
    @Inject
    public ActeurSessionStore(Provider<SessionId> sessionId) {
        this.sessionId = sessionId;
    }

    /**
     *
     * @param request
     * @return The http servlet request
     */
    protected final HttpEvent getHttpServletRequest(final Request request) {
        Object containerRequest = request.getContainerRequest();
        if (containerRequest == null || (containerRequest instanceof HttpEvent) == false) {
            throw new IllegalArgumentException("Request must be ServletWebRequest");
        }
        return (HttpEvent) containerRequest;
    }

    /**
     *
     * @see HttpServletRequest#getSession(boolean)
     *
     * @param request A Wicket request object
     * @param create If true, a session will be created if it is not existing
     * yet
     * @return The HttpSession associated with this request or null if
     * {@code create} is false and the {@code request} has no valid session
     */
    final SessionImpl getHttpSession(final Request request, final boolean create) {
        SessionId id = this.sessionId.get();
        SessionImpl sess = id == null ? null : sessions.get(id.toString());
        if (sess == null && create) {
            sess = new SessionImpl(request, id);
            bind(request, sess);
            sessions.put(id.toString(), sess);
        }
        return sess;
    }

    /**
     * @see org.apache.wicket.session.ISessionStore#bind(Request, Session)
     */
    @Override
    public final void bind(final Request request, final Session newSession) {
        if (getAttribute(request, Session.SESSION_ATTRIBUTE_NAME) != newSession) {
            // call template method
            onBind(request, newSession);
            for (BindListener listener : getBindListeners()) {
                listener.bindingSession(request, newSession);
            }

            Session httpSession = getHttpSession(request, false);

            if (httpSession != null) {
                // register an unbinding listener for cleaning up
                String applicationKey = Application.get().getName();
                httpSession.setAttribute("Wicket:SessionUnbindingListener-" + applicationKey,
                        new SessionBindingListener(applicationKey, newSession));

                // register the session object itself
                setAttribute(request, Session.SESSION_ATTRIBUTE_NAME, newSession);
            }
        }
    }

    @Override
    public void flushSession(Request request, Session session) {
        if (getAttribute(request, Session.SESSION_ATTRIBUTE_NAME) != session) {
            // this session is not yet bound, bind it
            bind(request, session);
        } else {
            setAttribute(request, Session.SESSION_ATTRIBUTE_NAME, session);
        }
    }

    /**
     * @see org.apache.wicket.session.ISessionStore#destroy()
     */
    @Override
    public void destroy() {
        try {
            for (Session s : sessions.values()) {
                s.detach();
            }
        } finally {
            sessions.clear();
        }
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#getSessionId(org.apache.wicket.request.Request,
     * boolean)
     */
    @Override
    public String getSessionId(final Request request, final boolean create) {
        return EnsureSessionId.findSessionId(((RequestAdapter) request).evt) + "";
    }

    /**
     * @see org.apache.wicket.session.ISessionStore#invalidate(Request)
     */
    @Override
    public final void invalidate(final Request request) {
        Session httpSession = getHttpSession(request, false);
        if (httpSession != null) {
            // tell the app server the session is no longer valid
            httpSession.invalidate();
        }
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#lookup(org.apache.wicket.request.Request)
     */
    @Override
    public final Session lookup(final Request request) {
        String sessionId = getSessionId(request, false);
        if (sessionId != null) {
            return (Session) getAttribute(request, Session.SESSION_ATTRIBUTE_NAME);
        }
        return null;
    }

    /**
     * Template method that is called when a session is being bound to the
     * session store. It is called <strong>before</strong> the session object
     * itself is added to this store (which is done by calling
     * {@link ISessionStore#setAttribute(Request, String, Serializable)} with
     * key {@link Session#SESSION_ATTRIBUTE_NAME}.
     *
     * @param request The request
     * @param newSession The new session
     */
    protected void onBind(final Request request, final Session newSession) {
    }

    /**
     * Template method that is called when the session is being detached from
     * the store, which typically happens when the {@link HttpSession} was
     * invalidated.
     *
     * @param sessionId The session id of the session that was invalidated.
     */
    protected void onUnbind(final String sessionId) {
        sessions.remove(new SessionId(sessionId));
    }

    /**
     * Gets the prefix for storing variables in the actual session (typically
     * {@link HttpSession}) for this application instance.
     *
     * @param request the request
     *
     * @return the prefix for storing variables in the actual session
     */
    private String getSessionAttributePrefix(final Request request) {
        String sessionAttributePrefix = MarkupParser.WICKET;

        if (request instanceof WebRequest) {
            sessionAttributePrefix = WebApplication.get().getSessionAttributePrefix(
                    (WebRequest) request, null);
        }

        return sessionAttributePrefix;
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#getAttribute(org.apache.wicket.request.Request,
     * java.lang.String)
     */
    @Override
    public final Serializable getAttribute(final Request request, final String name) {
        SessionImpl httpSession = getHttpSession(request, false);
        if (httpSession != null) {
            return (Serializable) httpSession.getAttribute(getSessionAttributePrefix(request) + name);
        }
        return null;
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#getAttributeNames(org.apache.wicket.request.Request)
     */
    @Override
    public final List<String> getAttributeNames(final Request request) {
        List<String> list = new ArrayList<String>();
        SessionImpl httpSession = getHttpSession(request, false);
        if (httpSession != null) {
            @SuppressWarnings("unchecked")
            final Iterator<String> names = httpSession.getAttributeNames().iterator();
            final String prefix = getSessionAttributePrefix(request);
            while (names.hasNext()) {
                final String name = names.next();
                if (name.startsWith(prefix)) {
                    list.add(name.substring(prefix.length()));
                }
            }
        }
        return list;
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#removeAttribute(org.apache.wicket.request.Request,
     * java.lang.String)
     */
    @Override
    public final void removeAttribute(final Request request, final String name) {
        SessionImpl httpSession = getHttpSession(request, false);
        if (httpSession != null) {
            String attributeName = getSessionAttributePrefix(request) + name;

            IRequestLogger logger = Application.get().getRequestLogger();
            if (logger != null) {
                Object value = httpSession.getAttribute(attributeName);
                if (value != null) {
                    logger.objectRemoved(value);
                }
            }
            httpSession.removeAttribute(attributeName);
        }
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#setAttribute(org.apache.wicket.request.Request,
     * java.lang.String, java.io.Serializable)
     */
    @Override
    public final void setAttribute(final Request request, final String name,
            final Serializable value) {
        // ignore call if the session was marked invalid
        SessionImpl httpSession = getHttpSession(request, false);
        if (httpSession != null) {
            String attributeName = getSessionAttributePrefix(request) + name;
            IRequestLogger logger = Application.get().getRequestLogger();
            if (logger != null) {
                if (httpSession.getAttribute(attributeName) == null) {
                    logger.objectCreated(value);
                } else {
                    logger.objectUpdated(value);
                }
            }
            httpSession.setAttribute(attributeName, value);
        }
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#registerUnboundListener(org.apache.wicket.session.ISessionStore.UnboundListener)
     */
    @Override
    public final void registerUnboundListener(final UnboundListener listener) {
        unboundListeners.add(listener);
    }

    /**
     * @see
     * org.apache.wicket.session.ISessionStore#unregisterUnboundListener(org.apache.wicket.session.ISessionStore.UnboundListener)
     */
    @Override
    public final void unregisterUnboundListener(final UnboundListener listener) {
        unboundListeners.remove(listener);
    }

    /**
     * @see org.apache.wicket.session.ISessionStore#getUnboundListener()
     */
    @Override
    public final Set<UnboundListener> getUnboundListener() {
        return Collections.unmodifiableSet(unboundListeners);
    }

    /**
     * Registers listener invoked when session is bound.
     *
     * @param listener
     */
    @Override
    public void registerBindListener(BindListener listener) {
        bindListeners.add(listener);
    }

    /**
     * Unregisters listener invoked when session is bound.
     *
     * @param listener
     */
    @Override
    public void unregisterBindListener(BindListener listener) {
        bindListeners.remove(listener);
    }

    /**
     * @return The list of registered bind listeners
     */
    @Override
    public Set<BindListener> getBindListeners() {
        return Collections.unmodifiableSet(bindListeners);
    }

    /**
     * Reacts on unbinding from the session by cleaning up the session related
     * data.
     */
    protected static final class SessionBindingListener
            implements
            HttpSessionBindingListener,
            Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * The unique key of the application within this web application.
         */
        private final String applicationKey;

        /**
         * The Wicket Session associated with the expiring HttpSession
         */
        private final Session wicketSession;

        /**
         * Construct.
         *
         * @param applicationKey The unique key of the application within this
         * web application
         * @param wicketSession The Wicket Session associated with the expiring
         * http session
         */
        public SessionBindingListener(final String applicationKey, final Session wicketSession) {
            this.applicationKey = applicationKey;
            this.wicketSession = wicketSession;
        }

        /**
         * @see
         * javax.servlet.http.HttpSessionBindingListener#valueBound(javax.servlet.http.HttpSessionBindingEvent)
         */
        @Override
        public void valueBound(final HttpSessionBindingEvent evg) {
        }

        /**
         * @see
         * javax.servlet.http.HttpSessionBindingListener#valueUnbound(javax.servlet.http.HttpSessionBindingEvent)
         */
        @Override
        public void valueUnbound(final HttpSessionBindingEvent evt) {
            String sessionId = evt.getSession().getId();

            log.debug("Session unbound: {}", sessionId);

            if (wicketSession != null) {
                wicketSession.onInvalidate();
            }

            Application application = Application.get(applicationKey);
            if (application == null) {
                log.debug("Wicket application with name '{}' not found.", applicationKey);
                return;
            }

            ISessionStore sessionStore = application.getSessionStore();
            if (sessionStore != null) {
                if (sessionStore instanceof ActeurSessionStore) {
                    ((ActeurSessionStore) sessionStore).onUnbind(sessionId);
                }

                for (UnboundListener listener : sessionStore.getUnboundListener()) {
                    listener.sessionUnbound(sessionId);
                }
            }
        }
    }

    class SessionImpl extends Session {

        private final String ua;
        private final SessionId id;

        public SessionImpl(Request request, SessionId id) {
            super(request);
            super.setAttribute(Session.SESSION_ATTRIBUTE_NAME, id.toString());
            RequestAdapter adap = (RequestAdapter) request;
            ua = adap.evt.getHeader(Headers.USER_AGENT);
            this.id = id;
        }

        @Override
        public ClientInfo getClientInfo() {
            return new WebClientInfo(RequestCycle.get(), ua);
        }

    }
}
