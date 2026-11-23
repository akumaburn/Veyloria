package com.eonmux.chatllm.services;

import com.eonmux.chatllm.map.StageContext;
import com.eonmux.chatllm.models.LocalArea;
import com.eonmux.chatllm.models.MapLocation;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocationResidentServiceTest {
    StageContext stage(String name,String description) {
        return StageContext.builder().node(MapLocation.builder().name("Village").build())
            .area(LocalArea.builder().name(name).areaType("room").description(description).build()).build();
    }
    @Test void staffedPlacesHaveResidentsButRoadsAndAbandonedPlacesDoNot() {
        assertEquals("Innkeeper",LocationResidentService.roleFor(stage("Inn","A warm common room")));
        assertEquals("Blacksmith",LocationResidentService.roleFor(stage("Smithy","A forge glows")));
        assertEquals("Merchant",LocationResidentService.roleFor(stage("Market square","Shops and stalls")));
        assertNull(LocationResidentService.roleFor(stage("Inn lane","The path to the inn")));
        assertNull(LocationResidentService.roleFor(stage("Inn","An abandoned inn")));
        var empty=stage("Inn",""); empty.getArea().setMetadata(new JsonObject().put("populated",false));
        assertNull(LocationResidentService.roleFor(empty));
    }
    @Test void entryRoomUsesWorldLocationAndExplicitRolesAreSupported() {
        var inn=stage("Common room",""); inn.getNode().setName("The Sleeping Dragon Inn"); inn.getArea().setIsEntry(true);
        assertEquals("Innkeeper",LocationResidentService.roleFor(inn));
        inn.getArea().setMetadata(new JsonObject().put("residentRole","Librarian"));
        assertEquals("Librarian",LocationResidentService.roleFor(inn));
        inn.getArea().setMetadata(new JsonObject().put("populated",true));
        inn.getArea().setIsEntry(false);
        assertEquals("Local resident",LocationResidentService.roleFor(inn));
        assertNull(LocationResidentService.roleFor(StageContext.empty()));
    }
    @Test void identitiesAreStableAndScopedToConversationAndArea() {
        String id=LocationResidentService.residentId("a","inn","Innkeeper");
        assertEquals(id,LocationResidentService.residentId("a","inn","innkeeper"));
        assertNotEquals(id,LocationResidentService.residentId("b","inn","Innkeeper"));
        assertNotEquals(id,LocationResidentService.residentId("a","other-inn","Innkeeper"));
    }
}
