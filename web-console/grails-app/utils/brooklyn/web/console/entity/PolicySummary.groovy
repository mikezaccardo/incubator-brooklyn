package brooklyn.web.console.entity;

import brooklyn.entity.Entity
import brooklyn.entity.EntityClass
import brooklyn.entity.Group
import brooklyn.entity.Effector
import brooklyn.entity.basic.AbstractEntity
import brooklyn.event.AttributeSensor
import brooklyn.event.Sensor
import brooklyn.location.Location
import brooklyn.location.basic.AbstractLocation
import brooklyn.policy.Policy
import brooklyn.policy.basic.AbstractPolicy

/** Summary of a Brookln Entity Location   */
public class PolicySummary {
    final String displayName
    final String policyStatus
    final String name
    final String id
    final String description

    public PolicySummary(Policy policy) {
        id = policy.findPolicyProperty('id')
        description = policy.findPolicyProperty('description')
        name = policy.findPolicyProperty('name')
        displayName = policy.findPolicyProperty('displayName')
        policyStatus = policy.findPolicyProperty('policyStatus')
        }

}


