package org.hl7.davinci.priorauth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.davinci.priorauth.Endpoint.RequestType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Subscription;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Subscription.SubscriptionChannelType;
import org.hl7.fhir.r4.model.Subscription.SubscriptionStatus;

import ca.uhn.fhir.parser.IParser;

/**
 * The Subscription endpoint to create new subscriptions or delete outdated
 * ones.
 */
@CrossOrigin
@RestController
@RequestMapping("/Subscription")
public class SubscriptionEndpoint {

    static final Logger logger = PALogger.getLogger();

    String REQUIRES_SUBSCRIPTION = "Prior Authorization Subscription Operation requires a Subscription resource";
    String SUBSCRIPTION_ADDED_SUCCESS = "Subscription successful";
    String PROCESS_FAILED = "Unable to process the request properly. Check the log for more details.";
    String INVALID_CHANNEL_TYPE = "Invalid channel type. Must be rest-hook or websocket";

    @GetMapping(value = "", produces = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
    public ResponseEntity<String> readSubscriptionJSON(HttpServletRequest request,
            @RequestParam(name = "identifier", required = false) String id,
            @RequestParam(name = "patient.identifier") String patient) {
        Map<String, Object> constraintMap = new HashMap<String, Object>();
        constraintMap.put("claimResponseId", id);
        constraintMap.put("patient", patient);
        return Endpoint.read(Table.SUBSCRIPTION, constraintMap, request, RequestType.JSON);
    }

    @GetMapping(value = "", produces = { MediaType.APPLICATION_XML_VALUE, "application/fhir+xml" })
    public ResponseEntity<String> readSubscriptionXML(HttpServletRequest request,
            @RequestParam(name = "identifier", required = false) String id,
            @RequestParam(name = "patient.identifier") String patient) {
        Map<String, Object> constraintMap = new HashMap<String, Object>();
        constraintMap.put("claimResponseId", id);
        constraintMap.put("patient", patient);
        return Endpoint.read(Table.SUBSCRIPTION, constraintMap, request, RequestType.XML);
    }

    @PostMapping(value = "", consumes = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
    public ResponseEntity<String> addSubscriptionJSON(HttpEntity<String> entity) {
        return addSubscription(entity.getBody(), RequestType.JSON);
    }

    @PostMapping(value = "", consumes = { MediaType.APPLICATION_XML_VALUE, "application/fhir+xml" })
    public ResponseEntity<String> addSubscriptionXML(HttpEntity<String> entity) {
        return addSubscription(entity.getBody(), RequestType.XML);
    }

    @DeleteMapping(value = "", consumes = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
    public ResponseEntity<String> deleteSubscriptionJSON(@RequestParam("identifier") String id,
            @RequestParam("patient.identifier") String patient) {
        return Endpoint.delete(id, patient, Table.SUBSCRIPTION, RequestType.JSON);
    }

    @DeleteMapping(value = "", consumes = { MediaType.APPLICATION_XML_VALUE, "application/fhir+xml" })
    public ResponseEntity<String> deleteSubscriptionXML(@RequestParam("identifier") String id,
            @RequestParam("patient.identifier") String patient) {
        return Endpoint.delete(id, patient, Table.SUBSCRIPTION, RequestType.XML);
    }

    private ResponseEntity<String> addSubscription(String body, RequestType requestType) {
        logger.info("POST /Subscription fhir+" + requestType.name());

        HttpStatus status = HttpStatus.OK;
        String formattedData = null;
        try {
            IParser parser = requestType == RequestType.JSON ? App.FHIR_CTX.newJsonParser()
                    : App.FHIR_CTX.newXmlParser();
            IBaseResource resource = parser.parseResource(body);
            if (resource instanceof Subscription) {
                Subscription subscription = (Subscription) resource;
                SubscriptionChannelType subscriptionType = subscription.getChannel().getType();

                // Check valid subscription type
                if (subscriptionType == SubscriptionChannelType.RESTHOOK
                        || subscriptionType == SubscriptionChannelType.WEBSOCKET) {
                    Subscription processedSubscription = processSubscription(subscription);
                    if (processedSubscription != null)
                        formattedData = FhirUtils.getFormattedData(processedSubscription, requestType);
                    else {
                        status = HttpStatus.BAD_REQUEST;
                        OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID,
                                PROCESS_FAILED);
                        formattedData = FhirUtils.getFormattedData(error, requestType);
                    }
                } else {
                    // Subscription must be rest-hook or websocket....
                    status = HttpStatus.BAD_REQUEST;
                    OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID,
                            INVALID_CHANNEL_TYPE);
                    formattedData = FhirUtils.getFormattedData(error, requestType);
                }
            } else {
                // Subscription is required...
                status = HttpStatus.BAD_REQUEST;
                OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.INVALID,
                        REQUIRES_SUBSCRIPTION);
                formattedData = FhirUtils.getFormattedData(error, requestType);
            }
        } catch (Exception e) {
            // The subscription failed so spectacularly that we need to
            // catch an exception and send back an error message...
            status = HttpStatus.BAD_REQUEST;
            OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.FATAL, IssueType.STRUCTURE, e.getMessage());
            formattedData = FhirUtils.getFormattedData(error, requestType);
        }
        MediaType contentType = requestType == RequestType.JSON ? MediaType.APPLICATION_JSON
                : MediaType.APPLICATION_XML;

        return ResponseEntity.status(status).contentType(contentType).body(formattedData);

    }

    private Subscription processSubscription(Subscription subscription) {
        // Get the criteria details parsed
        String claimResponseId = "";
        String patient = "";
        String status = "";
        String statusVarName = "status";
        String identifierVarName = "identifier";
        String patientIdentifierVarName = "patient.identifier";
        String criteria = subscription.getCriteria();
        String regex = "(.*)=(.*)&(.*)=(.*)&(.*)=(.*)";

        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(criteria);
        Map<String, String> criteriaMap = new HashMap<String, String>();

        if (matcher.find() && matcher.groupCount() == 6) {
            criteriaMap.put(matcher.group(1), matcher.group(2));
            criteriaMap.put(matcher.group(3), matcher.group(4));
            criteriaMap.put(matcher.group(5), matcher.group(6));
        } else {
            logger.fine("Subscription.criteria: " + criteria);
            logger.severe("Subcription.criteria is not in the form " + regex);
            return null;
        }

        // Determine which variable is which
        String variableName;
        for (Iterator<String> iterator = criteriaMap.keySet().iterator(); iterator.hasNext();) {
            variableName = iterator.next();
            if (variableName.equals(identifierVarName))
                claimResponseId = criteriaMap.get(variableName);
            if (variableName.equals(patientIdentifierVarName))
                patient = criteriaMap.get(variableName);
            if (variableName.equals(statusVarName))
                status = criteriaMap.get(variableName);
        }

        // Check the desired ClaimResponse is pended
        String outcome = App.getDB().readString(Table.CLAIM_RESPONSE, Collections.singletonMap("id", claimResponseId),
                "outcome");
        logger.info("SubscriptionEndpoint::Outcome for desired resource is: " + outcome);
        if (!outcome.equals(FhirUtils.ReviewAction.PENDED.asStringValue()))
            return null;

        // Add to db
        String id = UUID.randomUUID().toString();
        subscription.setId(id);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        logger.fine("SubscriptionEndpoint::Subscription given uuid " + id);
        Map<String, Object> dataMap = new HashMap<String, Object>();
        dataMap.put("id", id);
        dataMap.put("claimResponseId", claimResponseId);
        dataMap.put("patient", patient);
        dataMap.put("status", status);
        dataMap.put("resource", subscription);
        if (App.getDB().write(Table.SUBSCRIPTION, dataMap))
            return subscription;
        else
            return null;
    }
}
