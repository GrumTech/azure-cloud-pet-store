// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.chtrembl.petstoreassistant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.chtrembl.petstoreassistant.model.AzurePetStoreSessionInfo;
import com.chtrembl.petstoreassistant.model.DPResponse;
import com.chtrembl.petstoreassistant.service.AzureOpenAI.Classification;
import com.chtrembl.petstoreassistant.service.IAzureOpenAI;
import com.chtrembl.petstoreassistant.service.IAzurePetStore;
import com.chtrembl.petstoreassistant.utility.PetStoreAssistantUtilities;
import com.codepoetics.protonpack.collectors.CompletableFutures;
import com.fasterxml.jackson.databind.JsonNode;
import com.microsoft.bot.builder.ActivityHandler;
import com.microsoft.bot.builder.MessageFactory;
import com.microsoft.bot.builder.StatePropertyAccessor;
import com.microsoft.bot.builder.TurnContext;
import com.microsoft.bot.builder.UserState;
import com.microsoft.bot.schema.ChannelAccount;

/**
 * This class implements the functionality of the Bot.
 *
 * <p>
 * This is where application specific logic for interacting with the users would
 * be added. For this
 * sample, the {@link #onMessageActivity(TurnContext)} echos the text back to
 * the user. The {@link
 * #onMembersAdded(List, TurnContext)} will send a greeting to new conversation
 * participants.
 * </p>
 */
@Component
@Primary
public class PetStoreAssistantBot extends ActivityHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PetStoreAssistantBot.class);

    @Autowired
    private IAzureOpenAI azureOpenAI;
   
    @Autowired
    private IAzurePetStore azurePetStore;

    @Autowired
    private UserState userState;

    private String WELCOME_MESSAGE = "Hello and welcome to the Azure Pet Store, you can ask me questions about our products, your shopping cart and your order, you can also ask me for information about pet animals. How can I help you?";
            
    @Override
    protected CompletableFuture<Void> onMessageActivity(TurnContext turnContext) {
        String text = turnContext.getActivity().getText().toLowerCase();

        StatePropertyAccessor<String> sessionIDProperty = userState.createProperty("sessionID");
        StatePropertyAccessor<String> csrfTokenProperty = userState.createProperty("csrfToken");
            
        String sessionID = sessionIDProperty.get(turnContext).join();
        String csrfToken = csrfTokenProperty.get(turnContext).join();
        
        LOGGER.info("session: " + sessionID + " csrf: " + csrfToken);
      
        // strip out session id and csrf token if one was passed from soul machines sendTextMessage() function
        AzurePetStoreSessionInfo azurePetStoreSessionInfo = PetStoreAssistantUtilities.getAzurePetStoreSessionInfo(text);
        if(azurePetStoreSessionInfo != null)
        {
            text = azurePetStoreSessionInfo.getNewText();
            
            if (sessionID == null && csrfToken == null) {
                // set the props
                sessionIDProperty.set(turnContext, azurePetStoreSessionInfo.getSessionID()).join();
                csrfTokenProperty.set(turnContext, azurePetStoreSessionInfo.getCsrfToken()).join();
                // save the user state changes
                userState.saveChanges(turnContext).join();
                
                // send welcome message
               return turnContext.sendActivity(
               MessageFactory.text(this.WELCOME_MESSAGE)).thenApply(sendResult -> null);
            }
        }
        //if we have user state in the turn context use that instead
        else if (sessionID != null && csrfToken != null) {
            azurePetStoreSessionInfo = new AzurePetStoreSessionInfo(sessionID, csrfToken, text);
        }

        // for debugging during development :)
        if(text.equals("debug"))
        {
            return turnContext.sendActivity(
                    MessageFactory.text("your session id is "+sessionID+" and your csrf token is "+csrfToken)).thenApply(sendResult -> null);
        }

        DPResponse dpResponse = this.azureOpenAI.classification(text);
 
        switch (dpResponse.getClassification()) {
            case UPDATE_SHOPPING_CART:
                if(azurePetStoreSessionInfo != null)
                {
                    dpResponse = this.azureOpenAI.completion("find the product that is associated with the following text: \'" + text + "\'", Classification.SEARCH_FOR_PRODUCTS);
                    if(dpResponse.getResponseProductIDs() != null && dpResponse.getResponseProductIDs().size() == 1)
                    {
                        dpResponse = this.azurePetStore.updateCart(azurePetStoreSessionInfo, dpResponse.getResponseProductIDs().get(0));
                    }
                }
                else {
                    dpResponse.setDpResponseText("Once I get your session information, I will be able to update your shopping cart.");
                }
                break;
            case VIEW_SHOPPING_CART:
                dpResponse.setDpResponseText("Once I get your session information, I will be able to display your shopping cart.");
                break;
            case PLACE_ORDER:
                dpResponse.setDpResponseText("Once I get your session information, I will be able to place your order.");
                break;
            case SEARCH_FOR_PRODUCTS:
                dpResponse = this.azureOpenAI.completion(text, dpResponse.getClassification());
                break;
            case SOMETHING_ELSE:
                dpResponse = this.azureOpenAI.completion(text, dpResponse.getClassification());
                break; 
        }

        //only respond to the user if the user sent something (seems to be a bug where initial messages are sent without a prompt while page loads)
        if(StringUtils.isNotEmpty(text))
        {
            return turnContext.sendActivity(
                MessageFactory.text(dpResponse.getDpResponseText())).thenApply(sendResult -> null);
        }
        return null;
    }

    @Override
    protected CompletableFuture<Void> onMembersAdded(
            List<ChannelAccount> membersAdded,
            TurnContext turnContext) {
        
        return membersAdded.stream()
                .filter(
                        member -> !StringUtils
                                .equals(member.getId(), turnContext.getActivity().getRecipient().getId()))
                .map(channel -> turnContext
                        .sendActivity(
                                MessageFactory.text("")))
                .collect(CompletableFutures.toFutureList()).thenApply(resourceResponses -> null);
    }       
}
