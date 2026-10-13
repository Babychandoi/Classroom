package com.classroom.modules.assessment.doc;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * D-34: a node of a section - a GROUP (Q13 reading / Q21 listening container holding child items) or an ITEM. The JSON discriminator is
 * {@code kind}. A group's {@code children} are typed {@link ItemNode}, so a group nested in a group is rejected while parsing.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GroupNode.class, name = "GROUP"),
        @JsonSubTypes.Type(value = ItemNode.class, name = "ITEM")
})
public sealed interface Node permits GroupNode, ItemNode {
    String id();
}
