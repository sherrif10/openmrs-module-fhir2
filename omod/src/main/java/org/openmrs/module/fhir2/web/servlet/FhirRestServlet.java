/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.web.servlet;

import static org.openmrs.module.fhir2.FhirConstants.FHIR2_MODULE_ID;

import java.util.Collection;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.FifoMemoryPagingProvider;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.IServerAddressStrategy;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.LoggingInterceptor;
import lombok.AccessLevel;
import lombok.Setter;
import org.apache.commons.lang3.math.NumberUtils;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.fhir2.FhirActivator;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirGlobalPropertyService;
import org.openmrs.module.fhir2.narrative.OpenMRSThymeleafNarrativeGenerator;
import org.openmrs.module.fhir2.web.util.NarrativeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

@Component
@Setter(AccessLevel.PUBLIC)
public class FhirRestServlet extends RestfulServer {
	
	private static final long serialVersionUID = 1L;
	
	@Autowired
	private FhirGlobalPropertyService globalPropertyService;
	
	@Autowired
	@Qualifier("hapiLoggingInterceptor")
	private LoggingInterceptor loggingInterceptor;
	
	private ConfigurableApplicationContext ctx;
	
	@Autowired
	@Qualifier("messageSourceService")
	private MessageSource messageSource;
	
	@Override
	protected void initialize() {
		// we need to load the application context for the FHIR2 module
		if (globalPropertyService == null) {
			// get the activator which contains our ApplicationContext
			FhirActivator activator = (FhirActivator) ModuleFactory.getModuleById(FHIR2_MODULE_ID).getModuleActivator();
			ctx = activator.getApplicationContext();
			
			// reload ResourceProviders whenever the application context is refreshed
			ctx.addApplicationListener(e -> {
				if (e instanceof ContextRefreshedEvent) {
					unregisterProviders(getResourceProviders());
					registerProviders(ctx.getBean(getResourceProviderListName()));
				}
			});
			
			// ensure properties for this class are properly injected
			autoInject();
		}
		
		int defaultPageSize = NumberUtils
		        .toInt(globalPropertyService.getGlobalProperty(FhirConstants.OPENMRS_FHIR_DEFAULT_PAGE_SIZE), 10);
		int maximumPageSize = NumberUtils
		        .toInt(globalPropertyService.getGlobalProperty(FhirConstants.OPENMRS_FHIR_MAXIMUM_PAGE_SIZE), 100);
		
		FifoMemoryPagingProvider pp = new FifoMemoryPagingProvider(defaultPageSize);
		pp.setDefaultPageSize(defaultPageSize);
		pp.setMaximumPageSize(maximumPageSize);
		
		setPagingProvider(pp);
		setDefaultResponseEncoding(EncodingEnum.JSON);
		registerInterceptor(loggingInterceptor);
		
		String narrativesOverridePropertyFile = NarrativeUtils.getValidatedPropertiesFilePath(
		    globalPropertyService.getGlobalProperty(FhirConstants.NARRATIVES_OVERRIDE_PROPERTY_FILE, ""));
		
		String[] narrativePropertiesFiles;
		if (narrativesOverridePropertyFile != null) {
			narrativePropertiesFiles = new String[] { narrativesOverridePropertyFile,
			        FhirConstants.OPENMRS_NARRATIVES_PROPERTY_FILE, FhirConstants.HAPI_NARRATIVES_PROPERTY_FILE };
		} else {
			narrativePropertiesFiles = new String[] { FhirConstants.OPENMRS_NARRATIVES_PROPERTY_FILE,
			        FhirConstants.HAPI_NARRATIVES_PROPERTY_FILE };
		}
		
		getFhirContext()
		        .setNarrativeGenerator(new OpenMRSThymeleafNarrativeGenerator(messageSource, narrativePropertiesFiles));
	}
	
	protected void autoInject() {
		AutowiredAnnotationBeanPostProcessor bpp = new AutowiredAnnotationBeanPostProcessor();
		bpp.setBeanFactory(ctx.getAutowireCapableBeanFactory());
		bpp.processInjection(this);
	}
	
	protected String getResourceProviderListName() {
		return "fhirResources";
	}
	
	@Override
	protected String createPoweredByHeaderComponentName() {
		return FhirConstants.OPENMRS_FHIR_SERVER_NAME;
	}
	
	@Override
	protected String getRequestPath(String requestFullPath, String servletContextPath, String servletPath) {
		return requestFullPath
		        .substring(escapedLength(servletContextPath) + escapedLength(servletPath) + escapedLength("/fhir2Servlet"));
	}
	
	@Override
	@Autowired
	@Qualifier("fhirR4")
	public void setFhirContext(FhirContext theFhirContext) {
		super.setFhirContext(theFhirContext);
	}
	
	@Override
	@Autowired
	@Qualifier("fhirResources")
	public void setResourceProviders(Collection<IResourceProvider> theProviders) {
		super.setResourceProviders(theProviders);
	}
	
	@Override
	@Autowired
	public void setServerAddressStrategy(IServerAddressStrategy theServerAddressStrategy) {
		super.setServerAddressStrategy(theServerAddressStrategy);
	}
}
