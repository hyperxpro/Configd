package io.configd.server.fanout;

import io.configd.api.AclService;
import io.configd.api.AclService.Permission;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;

import java.util.Objects;
import java.util.Set;


public final class AclServiceWatchAuthorizer implements WatchAuthorizer {

    private final AclService aclService;

    
    public AclServiceWatchAuthorizer(AclService aclService) {
        this.aclService = Objects.requireNonNull(aclService, "aclService");
    }

    @Override
    public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
        // FULL / full_chain_verify -> root effective target ("") before the whole-subtree cover.
        if (target.fullChainVerify() || target.isFull()) {
            return aclService.authorizesWatch(aclService.effectiveRules(principal, roles), "");
        }
        // KEY -> the exact-key floor (WATCH and READ on the one key). Not coversTarget: its
        // interior-DENY term would wrongly reject an exact-key watch over a descendant-key DENY.
        if (target.targetKind() == EdgeFrame.WATCH_TARGET_KEY) {
            return aclService.isAllowed(principal, roles, target.path(), Permission.WATCH);
        }
        // PREFIX (subtree) -> whole-subtree cover (READ and WATCH over all of it, interior-DENY rejects).
        return aclService.authorizesWatch(aclService.effectiveRules(principal, roles), target.path());
    }

    
    @Override
    public boolean authorizeSubscribe(String principal, Set<String> roles) {
        return AclService.coversTarget(aclService.effectiveRules(principal, roles), "", Permission.READ);
    }

    
    @Override
    public long policyVersion() {
        return aclService.configPolicyVersion();
    }
}
