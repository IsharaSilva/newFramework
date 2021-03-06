/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
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
 */

package org.wso2.carbon.identity.application.authentication.framework.handler.request.impl;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.IObjectFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.application.authentication.framework.cache.AuthenticationResultCacheEntry;
import org.wso2.carbon.identity.application.authentication.framework.config.model.SequenceConfig;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.handler.sequence.impl
        .DefaultStepBasedSequenceHandler;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticationResult;
import org.wso2.carbon.identity.application.authentication.framework.model.CommonAuthResponseWrapper;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.authentication.framwork.test.utils.CommonTestUtils;
import org.wso2.carbon.identity.common.testng.WithCarbonHome;

import java.io.IOException;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.powermock.api.mockito.PowerMockito.doNothing;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

@PrepareForTest(FrameworkUtils.class)
@WithCarbonHome
public class DefaultAuthenticationRequestHandlerTest {

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    DefaultAuthenticationRequestHandler authenticationRequestHandler;

    @ObjectFactory
    public IObjectFactory getObjectFactory() {
        return new org.powermock.modules.testng.PowerMockObjectFactory();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        initMocks(this);
        authenticationRequestHandler = new DefaultAuthenticationRequestHandler();
    }

    @AfterMethod
    public void tearDown() throws Exception {
    }

    @Test
    public void testGetInstance() throws Exception {
        CommonTestUtils.testSingleton(
                DefaultAuthenticationRequestHandler.getInstance(),
                DefaultAuthenticationRequestHandler.getInstance()
        );
    }


    @Test
    public void testHandleDenyFromLoginPage() throws Exception {

        AuthenticationContext context = spy(new AuthenticationContext());
        context.setSequenceConfig(new SequenceConfig());

        DefaultAuthenticationRequestHandler authenticationRequestHandler =
                spy(new DefaultAuthenticationRequestHandler());

        // mock the conclude flow
        doNothing().when(authenticationRequestHandler).concludeFlow(request, response, context);
        doNothing().when(authenticationRequestHandler).sendResponse(request, response, context);

        // mock the context to show that flow is returning back from login page
        when(context.isReturning()).thenReturn(true);
        doReturn("DENY").when(request).getParameter(FrameworkConstants.RequestParams.DENY);

        authenticationRequestHandler.handle(request, response, context);

        assertFalse(context.isRequestAuthenticated());
    }


    @DataProvider(name = "rememberMeParamProvider")
    public Object[][] provideRememberMeParam() {

        return new Object[][]{
                {null, false},
                {"on", true},
                // any string other than "on"
                {"off", false}
        };
    }

    @Test(dataProvider = "rememberMeParamProvider")
    public void testHandleRememberMeOptionFromLoginPage(String rememberMeParam,
                                                        boolean expectedResult) throws Exception {

        doReturn(rememberMeParam).when(request).getParameter(FrameworkConstants.RequestParams.REMEMBER_ME);

        AuthenticationContext context = spy(new AuthenticationContext());
        SequenceConfig sequenceConfig = spy(new SequenceConfig());
        when(sequenceConfig.isCompleted()).thenReturn(true);

        context.setSequenceConfig(sequenceConfig);

        // mock the context to show that flow is returning back from login page
        when(context.isReturning()).thenReturn(true);
        when(context.getCurrentStep()).thenReturn(0);

        DefaultAuthenticationRequestHandler authenticationRequestHandler =
                spy(new DefaultAuthenticationRequestHandler());

        // Mock conclude flow and post authentication flows to isolate remember me option
        doNothing().when(authenticationRequestHandler).concludeFlow(request, response, context);
        doReturn(true).when(authenticationRequestHandler).isPostAuthenticationExtensionCompleted(context);

        authenticationRequestHandler.handle(request, response, context);

        assertEquals(context.isRememberMe(), expectedResult);
    }


    @DataProvider(name = "RequestParamDataProvider")
    public Object[][] provideSequenceStartRequestParams() {

        return new Object[][]{
                {"true", true},
                {"false", false},
                {null, false}
        };
    }

    @Test(dataProvider = "RequestParamDataProvider")
    public void testHandleSequenceStart(String paramValue,
                                        boolean expectedResult) throws Exception {

        AuthenticationContext context = new AuthenticationContext();

        // ForceAuth
        doReturn(paramValue).when(request).getParameter(FrameworkConstants.RequestParams.FORCE_AUTHENTICATE);
        assertFalse(authenticationRequestHandler.handleSequenceStart(request, response, context));
        assertEquals(context.isForceAuthenticate(), expectedResult);

        // Reauthenticate
        doReturn(paramValue).when(request).getParameter(FrameworkConstants.RequestParams.RE_AUTHENTICATE);
        assertFalse(authenticationRequestHandler.handleSequenceStart(request, response, context));
        assertEquals(context.isReAuthenticate(), expectedResult);

        // PassiveAuth
        doReturn(paramValue).when(request).getParameter(FrameworkConstants.RequestParams.PASSIVE_AUTHENTICATION);
        assertFalse(authenticationRequestHandler.handleSequenceStart(request, response, context));
        assertEquals(context.isPassiveAuthenticate(), expectedResult);
    }

    @Test
    public void testConcludeFlow() throws Exception {
    }


    @DataProvider(name = "sendResponseDataProvider")
    public Object[][] provideSendResponseData() {
        return new Object[][]{
                {true, true, "/samlsso", "dummy_data_key", "/samlsso?sessionDataKey=dummy_data_key&chkRemember=on"},
                {true, false, "/samlsso", "dummy_data_key", "/samlsso?sessionDataKey=dummy_data_key"},
                {false, true, "/samlsso", "dummy_data_key", "/samlsso?sessionDataKey=dummy_data_key"},
                {true, true, "/samlsso", null, "/samlsso?chkRemember=on"}
        };
    }

    @Test(dataProvider = "sendResponseDataProvider")
    public void testSendResponse(boolean isRequestAuthenticated,
                                 boolean isRememberMe,
                                 String callerPath,
                                 String sessionDataKey,
                                 String expectedRedirectUrl) throws Exception {

        AuthenticationContext context = new AuthenticationContext();
        context.setRequestAuthenticated(isRequestAuthenticated);
        context.setRememberMe(isRememberMe);
        context.setCallerPath(callerPath);
        context.setCallerSessionKey(sessionDataKey);

        SequenceConfig sequenceConfig = spy(new SequenceConfig());
        context.setSequenceConfig(sequenceConfig);

        DefaultAuthenticationRequestHandler requestHandler = spy(new DefaultAuthenticationRequestHandler());
        doNothing().when(requestHandler).populateErrorInformation(request, response, context);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        requestHandler.sendResponse(request, response, context);
        verify(response).sendRedirect(captor.capture());
        assertEquals(captor.getValue(), expectedRedirectUrl);
    }


    @Test(expectedExceptions = FrameworkException.class)
    public void testSendResponseException() throws Exception {

        AuthenticationContext context = new AuthenticationContext();
        context.setRequestAuthenticated(true);
        context.setRememberMe(true);
        context.setCallerPath("/samlsso");
        String sessionDataKey = UUID.randomUUID().toString();
        context.setCallerSessionKey(sessionDataKey);

        SequenceConfig sequenceConfig = spy(new SequenceConfig());
        context.setSequenceConfig(sequenceConfig);

        doThrow(new IOException()).when(response).sendRedirect(anyString());
        authenticationRequestHandler.sendResponse(request, response, context);
    }

    @Test
    public void testHandleAuthorization() throws Exception {
    }

    @DataProvider(name = "postAuthExtensionParam")
    public Object[][] getPostAuthExtensionParam() {

        return new Object[][]{
                {Boolean.TRUE, true},
                {Boolean.FALSE, false},
                {null, false},
                {new Object(), false}
        };
    }

    @Test(dataProvider = "postAuthExtensionParam")
    public void testIsPostAuthenticationExtensionCompleted(Object postAuthExtensionCompleted,
                                                           boolean expectedResult) throws Exception {

        AuthenticationContext authenticationContext = new AuthenticationContext();
        authenticationContext
                .setProperty(FrameworkConstants.POST_AUTHENTICATION_EXTENSION_COMPLETED, postAuthExtensionCompleted);

        assertEquals(
                authenticationRequestHandler.isPostAuthenticationExtensionCompleted(authenticationContext),
                expectedResult
        );
    }

    @DataProvider(name = "errorInfoDataProvider")
    public Object[][] getErrorInfoFormationData() {

        return new Object[][]{
                {"error_code", "error_message", "error_uri", "samlsso"},
                {null, "error_message", "error_uri", "other"},
                {"error_code", null, "error_uri", "other"},
                {"error_code", "error_message", null, "other"},
                {"error_code", "error_message", "error_uri", "other"}
        };

    }


    @Test(dataProvider = "errorInfoDataProvider")
    public void testPopulateErrorInformation(String errorCode,
                                             String errorMessage,
                                             String errorUri,
                                             String requestType) throws Exception {

        AuthenticationResult authenticationResult = new AuthenticationResult();
        doReturn(authenticationResult).when(request).getAttribute(FrameworkConstants.RequestAttribute.AUTH_RESULT);

        // Populate the context with error details
        AuthenticationContext context = new AuthenticationContext();
        context.setProperty(FrameworkConstants.AUTH_ERROR_CODE, errorCode);
        context.setProperty(FrameworkConstants.AUTH_ERROR_MSG, errorMessage);
        context.setProperty(FrameworkConstants.AUTH_ERROR_URI, errorUri);


        // request type is does not cache authentication result
        context.setRequestType(requestType);
        response = spy(new CommonAuthResponseWrapper(response));

        // if request type caches authentication result we need to mock required dependent objects
        AuthenticationResultCacheEntry cacheEntry = spy(new AuthenticationResultCacheEntry());
        when(cacheEntry.getResult()).thenReturn(authenticationResult);
        mockStatic(FrameworkUtils.class);
        when(FrameworkUtils.getAuthenticationResultFromCache(anyString())).thenReturn(cacheEntry);


        authenticationRequestHandler.populateErrorInformation(request, response, context);

        // Assert stuff
        AuthenticationResult modifiedAuthenticationResult =
                (AuthenticationResult) request.getAttribute(FrameworkConstants.RequestAttribute.AUTH_RESULT);

        assertNotNull(modifiedAuthenticationResult);
        assertEquals(modifiedAuthenticationResult.getProperty(FrameworkConstants.AUTH_ERROR_CODE), errorCode);
        assertEquals(modifiedAuthenticationResult.getProperty(FrameworkConstants.AUTH_ERROR_MSG), errorMessage);
        assertEquals(modifiedAuthenticationResult.getProperty(FrameworkConstants.AUTH_ERROR_URI), errorUri);
    }

}

