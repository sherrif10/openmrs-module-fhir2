/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.fhir2.api.translators.impl;

import static org.openmrs.module.fhir2.FhirConstants.ADMINISTERING_ENCOUNTER_ROLE_PROPERTY;
import static org.openmrs.module.fhir2.FhirConstants.IMMUNIZATIONS_ENCOUNTER_TYPE_PROPERTY;
import static org.openmrs.module.fhir2.api.translators.impl.ImmunizationTranslatorImpl.immunizationConcepts;
import static org.openmrs.module.fhir2.api.translators.impl.ImmunizationTranslatorImpl.immunizationGroupingConcept;
import static org.openmrs.module.fhir2.api.util.FhirUtils.createExceptionErrorOperationOutcome;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import lombok.AccessLevel;
import lombok.Setter;
import org.apache.commons.lang.Validate;
import org.openmrs.Concept;
import org.openmrs.EncounterRole;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.Provider;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.EncounterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Setter(AccessLevel.PACKAGE)
public class ImmunizationObsGroupHelper {
	
	@Autowired
	private ConceptService conceptService;
	
	@Autowired
	private EncounterService encounterService;
	
	@Autowired
	private AdministrationService adminService;
	
	public EncounterType getImmunizationsEncounterType() throws InvalidRequestException {
		String errMsg = "The Immunization resource requires an immunizations encounter type to be defined in the global property '"
		        + IMMUNIZATIONS_ENCOUNTER_TYPE_PROPERTY
		        + "', but no immunizations encounter type is defined for this instance.";
		String uuid = adminService.getGlobalProperty(IMMUNIZATIONS_ENCOUNTER_TYPE_PROPERTY);
		return Optional.of(encounterService.getEncounterTypeByUuid(uuid))
		        .orElseThrow(() -> new InvalidRequestException(errMsg, createExceptionErrorOperationOutcome(errMsg)));
	}
	
	public EncounterRole getAdministeringEncounterRole() throws InvalidRequestException {
		String errMsg = "The Immunization resource requires an administering encounter role to be defined in the global property '"
		        + ADMINISTERING_ENCOUNTER_ROLE_PROPERTY
		        + "', but no administering encounter role is defined for this instance.";
		String uuid = adminService.getGlobalProperty(ADMINISTERING_ENCOUNTER_ROLE_PROPERTY);
		return Optional.of(encounterService.getEncounterRoleByUuid(uuid))
		        .orElseThrow(() -> new InvalidRequestException(errMsg, createExceptionErrorOperationOutcome(errMsg)));
	}
	
	public Concept concept(String refTerm) throws InvalidRequestException {
		String errMsg = "The Immunization resource requires a concept mapped to '" + refTerm
		        + "', however either multiple concepts are mapped to that term or not concepts are mapped to that term.";
		String[] mapping = refTerm.split(":");
		return Optional.of(conceptService.getConceptByMapping(mapping[1], mapping[0]))
		        .orElseThrow(() -> new InvalidRequestException(errMsg, createExceptionErrorOperationOutcome(errMsg)));
	}
	
	public Obs newImmunizationObsGroup() {
		Obs obs = new Obs();
		obs.setConcept(concept(immunizationGroupingConcept));
		obs.setObsDatetime(new Date());
		
		immunizationConcepts.stream().forEach(refTerm -> {
			Obs o = new Obs();
			o.setConcept(concept(refTerm));
			o.setObsDatetime(obs.getObsDatetime());
			obs.addGroupMember(o);
		});
		
		return obs;
	}
	
	public Provider getAdministeringProvider(Obs obs) throws InvalidRequestException {
		EncounterRole role = getAdministeringEncounterRole();
		String errMsg = "The Immunization resource is required to be attached to an OpenMRS encounter involving a single encounter provider with the role '"
		        + role.getName() + "'. This is not the case for immunization '" + obs.getUuid() + "' attached to encounter '"
		        + obs.getEncounter().getUuid() + "'.";
		return obs.getEncounter().getProvidersByRole(role).stream().findFirst()
		        .orElseThrow(() -> new InvalidRequestException(errMsg, createExceptionErrorOperationOutcome(errMsg)));
	}
	
	public void validateImmunizationObsGroup(Obs obs) throws InvalidRequestException {
		
		if (!concept(immunizationGroupingConcept).equals(obs.getConcept())) {
			String errMsg = "The Immunization resource requires the underlying OpenMRS immunization obs group to be defined by a concept mapped as same as "
			        + immunizationGroupingConcept + ". That is not the case for obs '" + obs.getUuid()
			        + "' that is defined by the concept named '" + obs.getConcept().getName().toString() + "'.";
			throw new InvalidRequestException(errMsg, createExceptionErrorOperationOutcome(errMsg));
		}
		
		final Set<String> refConcepts = immunizationConcepts.stream()
		        .map(m -> conceptService.getConceptByMapping(m.split(":")[1], m.split(":")[0])).map(c -> c.getUuid())
		        .collect(Collectors.toSet());
		
		// filtering the obs' concepts that are immunization concepts (but there could be others)
		List<String> obsConcepts = obs.getGroupMembers().stream().map(o -> o.getConcept().getUuid())
		        .filter(uuid -> refConcepts.contains(uuid)).collect(Collectors.toList());
		
		Validate.notEmpty(obsConcepts);
		// each immunization concept should define only one obs of the group
		obsConcepts.stream().forEach(uuid -> {
			if (refConcepts.contains(uuid)) {
				refConcepts.remove(uuid);
			} else {
				String errMsg = "The immunization obs member defined by concept with UUID '" + uuid
				        + "' is found multiple times in the immunization obs group.";
				throw new InvalidRequestException(errMsg, createExceptionErrorOperationOutcome(errMsg));
			}
		});
		Validate.isTrue(refConcepts.size() == 0);
	}
	
	/**
	 * @param obs An obs group
	 * @return A mapping from CIEL reference terms to obs of all obs group members
	 */
	public Map<String, Obs> getObsMembersMap(Obs obs) {
		Map<String, Obs> members = new HashMap<String, Obs>();
		obs.getGroupMembers().stream().forEach(o -> {
			immunizationConcepts.stream().forEach(refTerm -> {
				if (o.getConcept().equals(concept(refTerm))) {
					members.put(refTerm, o);
					
				}
			});
		});
		return members;
	}
	
}
