# Configd Driver-Protocol Conformance Coverage

**Generated + asserted by `CoverageAuditTest`** against `catalog-clauses.txt` (the 244 normative clauses transcribed from the requirements catalog). Do not hand-edit — a change here must come from adding a covering case or an honest SKIP, then regenerating.

## Tally

- Total clauses: 244
- **Covered** (a `@Tag("clause:…")` conformance case asserts it): 196
- **Skipped** (explicit, reasoned): 48
  - SKIP:guidance: 2
  - SKIP:model: 33
  - SKIP:not-in-v1: 4
  - SKIP:not-testable-v1: 4
  - SKIP:operator: 3
  - SKIP:reserved: 2

## Per-clause breakdown

| Clause | Plane | Holder | Kind | Status |
|---|---|---|---|---|
| OV2-1 | both | C | LIVE | COVERED — writesAreHttpOnlyTheEdgePlaneHasNoWriteSurface() |
| OV2-2 | both | C | NO | SKIP:model (no (implementation-shape statement)) |
| OV2-3 | both | C | LIVE | COVERED — thePlanesAreNotInterchangeableNoWatchOverHttpNoWriteOverEdge() |
| OV3-1 | meta | C | NO | SKIP:guidance (no (guidance)) |
| OV4-1 | both | C | LIVE | COVERED — thereAreTwoIndependentVersionMechanismsAndNeitherIsANegotiation() |
| OV4-2 | both | C | LIVE | COVERED — NoVersionNegotiationTest, edgeFirstFramePinRejectsAnyOtherAcceptedVersionNoDowngrade(), edgeUnknownWireVersionFailsClosedNoDowngrade(), everyFrameStampsAPinnedVersionNoUnversionedPreamble(), httpClientOnlyAddressesV1AndTreatsUnknownPathAsTerminal() |
| OV5-1..5-3 | meta | C | NO | SKIP:guidance (no (defines suite scope)) |
| OV5-4 | both | C | LIVE | COVERED — aV1DriverIsVectorNativeAndLeaderFollowingEvenAtN1() |
| OV5-5 | both | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| OV6-1 | edge | S/operator | NO | SKIP:operator (no (deployment requirement; mTLS does not scope cert→frame-type)) |
| OV6-2 | both | C | LIVE | COVERED — RealServerAuthModesTest, basicAuthAgainstRealServer(), mtlsAuthAgainstRealServer(), tokenAuthAgainstRealServer() |
| OV7-1 | both | C | PARTIAL | SKIP:model (partial (client-side; test client refuses plaintext/no-SAN)) |
| OV7-2 | both | C | NO | SKIP:model (no (trust-model statement)) |
| OV7-3 | both | C | LIVE | COVERED — failsClosedOnAnUnknownStatusAndOnAnUnrecognizedSuccessBody() |
| OV7-4_1 | edge | C | LIVE | COVERED — NoVersionNegotiationTest, edgeFirstFramePinRejectsAnyOtherAcceptedVersionNoDowngrade(), edgeUnknownWireVersionFailsClosedNoDowngrade(), everyFrameStampsAPinnedVersionNoUnversionedPreamble(), httpClientOnlyAddressesV1AndTreatsUnknownPathAsTerminal() |
| OV7-4_2 | both | C | LIVE | COVERED — NoVersionNegotiationTest, edgeFirstFramePinRejectsAnyOtherAcceptedVersionNoDowngrade(), edgeUnknownWireVersionFailsClosedNoDowngrade(), everyFrameStampsAPinnedVersionNoUnversionedPreamble(), httpClientOnlyAddressesV1AndTreatsUnknownPathAsTerminal() |
| OV7-4_3 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| OV8-1 | meta | — | NO | SKIP:model (no) |
| OV8-2 | both | C | LIVE | COVERED — NoVersionNegotiationTest, edgeFirstFramePinRejectsAnyOtherAcceptedVersionNoDowngrade(), edgeUnknownWireVersionFailsClosedNoDowngrade(), everyFrameStampsAPinnedVersionNoUnversionedPreamble(), httpClientOnlyAddressesV1AndTreatsUnknownPathAsTerminal() |
| A1.3 | both | C | NO | SKIP:model (no (client-internal authz model)) |
| A2-1 | both | C | LIVE | COVERED — scopeIsATypedFieldNeverAPathSegment() |
| A2-2 | both | C | NO | SKIP:model (no (model)) |
| A2-3 | both | C | LIVE | COVERED — scopeDefaultsToGlobalAndIsOmittedOnTheHttpWire() |
| A2-4_INV-PATH | both | C | NO | SKIP:not-testable-v1 (no (client MUST-NOT; observable only as no-co-location assumption)) |
| A3-1..A3-3 | both | C | LIVE | COVERED — clientRejectsIllegalPathsBeforeTheWire() |
| A3-4 | both | C | LIVE | COVERED — ServerObeysPathAliasingTest, clientRejectsNonCanonicalPathsBeforeTheWire(), serverIndependentlyEnforcesKeyCanonicalityDefenseInDepth(), serverNeverAliasesTraversalOrEmptySegmentSpellingsToASensitiveKey() |
| A3-5 | both | C | LIVE | COVERED — clientRejectsAnOversizePathBeforeTheWire() |
| A3-6 | both | C | NO | SKIP:reserved (LIST is a reserved, non-grantable capability - no list wire by design and the policy parser rejects any grant of it) |
| A4-1 | both | C | NO | SKIP:not-testable-v1 (no (server-side)) |
| A4-2 | both | C | LIVE | COVERED — watchOrderingIsPerShardOnlyNeverAssumedCrossShard() |
| A4-3..A4-6 | HTTP | C | NO | SKIP:reserved (LIST is a reserved, non-grantable capability - no list wire by design and the policy parser rejects any grant of it) |
| A4-7 | HTTP | C | LIVE | COVERED — deleteAddressesExactlyOneConcreteKeyWithNoRecursiveSurface() |
| A5-1 | both | C | NO | SKIP:model (no (authz model)) |
| A5-2 | both | C | LIVE | COVERED — watchRequiresBothReadAndWatchNotEitherAlone() |
| A5-3 | both | C | NO | SKIP:model (no) |
| A5-4 | both | S/both | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer() |
| A6-1 | edge | S | LIVE | COVERED — fullChainVerifyAndFullTargetsRequireRootScope(), interiorReadDenySinksTheWholeSubtreeWatch(), overBroadTargetIsRejectedNotSilentlyNarrowed(), unauthorizedSubscriptionIsTerminal403ClassWithZeroDataFrames(), watchRequiresBothReadAndWatchNotEitherAlone() |
| A6-2 | edge | S | LIVE | COVERED — overBroadTargetIsRejectedNotSilentlyNarrowed() |
| A6-3 | edge | S | LIVE | COVERED — fullChainVerifyAndFullTargetsRequireRootScope() |
| A6-4_INV-WATCH-READ | edge | S | LIVE | COVERED — interiorReadDenySinksTheWholeSubtreeWatch() |
| A6-5 | edge | S | LIVE | COVERED — unauthorizedSubscriptionIsTerminal403ClassWithZeroDataFrames() |
| A7-1 | both | S | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer() |
| A7-2 | both | both | LIVE | COVERED — RealServerHttpTest, forbidden403IsTerminalUnauthenticated401DoesNotHotLoop(), getPutDeleteAndAdminAgainstRealServer() |
| A8-1 | both | C | NO | SKIP:model (no (client convention)) |
| A8-2 | both | C | LIVE | COVERED — scopeDefaultsToGlobalAndIsOmittedOnTheHttpWire(), scopeIsATypedFieldNeverAPathSegment() |
| A9-1 | both | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| A9-2 | both | C | NO | SKIP:model (no (client presentation)) |
| A9-3 | edge | S | LIVE | COVERED — ServerObeysPathAuthzTest, fullChainVerifyAndFullTargetsRequireRootScope(), interiorReadDenySinksTheWholeSubtreeWatch(), overBroadTargetIsRejectedNotSilentlyNarrowed(), serverFailsClosedOnAnUnrecognizedScopeOrdinal(), serverIndependentlyEnforcesKeyCanonicalityDefenseInDepth(), unauthorizedSubscriptionIsTerminal403ClassWithZeroDataFrames(), watchOrderingIsPerShardOnlyNeverAssumedCrossShard(), watchRequiresBothReadAndWatchNotEitherAlone() |
| A9-4 | both | C | LIVE | COVERED — clientCannotExpressAnUnrecognizedCapabilityOrTargetIdentifier(), serverFailsClosedOnAnUnrecognizedScopeOrdinal() |
| W1-1 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| W1-2 | edge | both | LIVE | COVERED — RealServerWatchTest, clientWatchTailsRealFanOutServerAndAdvancesCursor(), cursoredShareIsRefusedW8_6a(), twoFromNowWatchesShareOneRealConnection() |
| W1-3 | edge | both | LIVE | COVERED — watchConnectionNegotiates0x02PerConnectionEndToEnd() |
| W2-1..W2-4 | edge | C | LIVE | COVERED — keyWatchIsPersistentSingleShardAndTargetsAreCanonical() |
| W2-5 | edge | C | LIVE | COVERED — keyWatchIsPersistentSingleShardAndTargetsAreCanonical() |
| W2-6 | edge | both | LIVE | COVERED — resumeReSendsTheSavedCursorVectorOnAFreshWatchCreate() |
| W2-7 | edge | C | NO | SKIP:model (no (client design)) |
| W2-8 | edge | both | LIVE | COVERED — gapUnrecoverableHasNoOldestVectorAndDriverReBootstrapsWithAFreshWatchId() |
| W2-9 | edge | C | NO | SKIP:model (no (client model)) |
| W3-1 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| W3-2 | edge | S | NO | SKIP:not-testable-v1 (no (server property)) |
| W3-3 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| W3-4 | edge | C | LIVE | COVERED — RealServerWatchTest, clientWatchTailsRealFanOutServerAndAdvancesCursor(), cursoredShareIsRefusedW8_6a(), twoFromNowWatchesShareOneRealConnection() |
| W3-5 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| W3-6 | edge | C | NO | SKIP:model (no (client-internal)) |
| W3-7 | edge | both | LIVE | COVERED — RealServerWatchTest, clientWatchTailsRealFanOutServerAndAdvancesCursor(), cursoredShareIsRefusedW8_6a(), twoFromNowWatchesShareOneRealConnection() |
| W4-1..W4-5 | edge | both | LIVE | COVERED — keyWatchIsPersistentSingleShardAndTargetsAreCanonical(), multiShardUnionMergeDedupsByGidAndSAndPresentsPerShardOrderOnly(), watchProgressBookmarkAdvancesIdleCursorWithoutRegressing() |
| W5-1 | edge | both | CODEC | COVERED — watchCreatedIsTheLiveSignalCarryingThePerShardModeVector() |
| W5-2 | edge | both | CODEC | COVERED — fullChainVerifyVerifiesSignaturesAndFiltersLocally() |
| W5-3 | edge | both | CODEC | COVERED — perShardInlineCatchUpTaggedByWatchIdGidWhileOtherShardKeepsStreaming() |
| W5-4 | edge | S | LIVE | COVERED — authorizedNarrowWatchIsAdmittedThenStreams() |
| W5-4a | edge | both | LIVE | COVERED — watchCreateFlagsByteEncodesTheThreeRequestBits() |
| W5-4b | edge | C | NO | SKIP:not-in-v1 (no (N>1 semantics)) |
| W5-5 | edge | both | LIVE | COVERED — watchCreatedIsTheLiveSignalCarryingThePerShardModeVector() |
| W5-6 | edge | both | CODEC | COVERED — oneWatchEventIsBatchAtomicWithSignedDeleteLenAndAscendingS() |
| W5-7 | edge | both | LIVE | COVERED — watchProgressBookmarkAdvancesIdleCursorWithoutRegressing() |
| W5-8 | edge | both | LIVE | COVERED — clientInitiatedCancelSendsWatchCancelByWatchId() |
| W5-9 | edge | both | CODEC | COVERED — notAuthorizedIsAPerWatchTerminalTheClientDoesNotRetry() |
| W5-9a | edge | S/C | LIVE | COVERED — gapUnrecoverableHasNoOldestVectorAndDriverReBootstrapsWithAFreshWatchId() |
| W5-10 | edge | both | LIVE | COVERED — perShardInlineCatchUpTaggedByWatchIdGidWhileOtherShardKeepsStreaming() |
| W5-11 | edge | both | LIVE | COVERED — watchConnectionNegotiates0x02PerConnectionEndToEnd() |
| W5-12 | edge | C | CODEC | COVERED — aWatchUsesWatchCreateWithAVectorCursorNotAScalarSubscribe() |
| W6-1 | edge | C | LIVE | COVERED — multiShardUnionMergeDedupsByGidAndSAndPresentsPerShardOrderOnly() |
| W6-2_W6-2a | edge | C | NO | SKIP:model (no (client presentation)) |
| W6-3 | edge | both | LIVE | COVERED — perShardInlineCatchUpTaggedByWatchIdGidWhileOtherShardKeepsStreaming() |
| W6-4 | edge | both | LIVE | COVERED — RealServerWatchTest, clientWatchTailsRealFanOutServerAndAdvancesCursor(), cursoredShareIsRefusedW8_6a(), twoFromNowWatchesShareOneRealConnection() |
| W6-5 | edge | C | LIVE | COVERED — watchProgressBookmarkAdvancesIdleCursorWithoutRegressing() |
| W6-6 | edge | C | NO | SKIP:model (no (client model)) |
| W7-1..W7-4 | edge | S | LIVE | COVERED — authorizedNarrowWatchIsAdmittedThenStreams(), fullChainVerifyWithoutRootScopeIsRejectedWithNoChainLeaked(), overBroadTargetIsRejectedNotSilentlyNarrowedWithZeroDataFrames() |
| W7-5_W7-5a | edge | S | LIVE | COVERED — fullChainVerifyWithoutRootScopeIsRejectedWithNoChainLeaked(), overBroadTargetIsRejectedNotSilentlyNarrowedWithZeroDataFrames() |
| W7-6 | edge | C | LIVE | COVERED — notAuthorizedIsAPerWatchTerminalTheClientDoesNotRetry() |
| W7-7 | edge | S | LIVE | COVERED — liveWatchIsForceClosedWithinBoundedLatencyOnPolicyVersionAdvance() |
| W8-1 | edge | both | NO | SKIP:model (no (freshness property)) |
| W8-2 | edge | both | LIVE | COVERED — authorizedNarrowWatchIsAdmittedThenStreams() |
| W8-3 | edge | C | CODEC | COVERED — commitTimestampIsFreshnessNeverTheCursor() |
| W8-4 | edge | both | LIVE | COVERED — fullChainVerifyVerifiesSignaturesAndFiltersLocally() |
| W8-5 | edge | — | NO | SKIP:model (no) |
| W8-6 | edge | C | LIVE | COVERED — RealServerWatchTest, clientWatchTailsRealFanOutServerAndAdvancesCursor(), cursoredShareIsRefusedW8_6a(), twoFromNowWatchesShareOneRealConnection() |
| W8-7 | edge | S | NO | SKIP:model (no) |
| W9-1..W9-3 | edge | S | NO | SKIP:not-testable-v1 (no (server-build note)) |
| W10-1 | both | C | NO | SKIP:model (no) |
| W10-2..W10-8 | edge | C | LIVE | COVERED — prevValueRequestedButUnsupportedStillDeliversEvents() |
| AU2-1 | both | C | LIVE | COVERED — RealServerAuthModesTest, basicAuthAgainstRealServer(), mtlsAuthAgainstRealServer(), tokenAuthAgainstRealServer() |
| AU2-2 | both | C | NO | SKIP:model (no (client-internal)) |
| AU2-3 | both | C | LIVE | COVERED — RealServerAuthModesTest, basicAuthAgainstRealServer(), mtlsAuthAgainstRealServer(), tokenAuthAgainstRealServer() |
| AU2-4 | both | C | LIVE | COVERED — RealServerAuthModesTest, basicAuthAgainstRealServer(), mtlsAuthAgainstRealServer(), tokenAuthAgainstRealServer() |
| AU3-1 | HTTP | C | LIVE | COVERED — RealServerAuthModesTest, basicAuthAgainstRealServer(), mtlsAuthAgainstRealServer(), tokenAuthAgainstRealServer() |
| AU3-2 | edge | both | LIVE | COVERED — RealServerAuthModesTest, basicAuthAgainstRealServer(), mtlsAuthAgainstRealServer(), tokenAuthAgainstRealServer() |
| AU3-3 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| AU3-4 | both | C | PARTIAL | SKIP:model (partial (client-side)) |
| AU3-5 | (interior) | — | NO | SKIP:not-in-v1 (no (out of driver scope)) |
| AU4-1 | both | both | LIVE | COVERED — authenticatesBeforeAnyBusinessSubscribeFrame() |
| AU4-2 | both | C | LIVE | COVERED — bearerIsPresentedPerRequestNotPersistedServerSide() |
| AU4-3 | both | C | LIVE | COVERED — driverPresentsAgainstAuthDisabledYet401StillMeansAuthRequired() |
| AU4-4 | edge | C | LIVE | COVERED — rejectedCredentialRecoversViaBoundedReconnectNeverHotLoops(), singlePreAuthFrameIsFirstAndReachesAuthenticated() |
| AU4-5 | edge | C | LIVE | COVERED — pipelinesBusinessFrameBehindAuthWithNoAck() |
| AU4-6 | edge | C | LIVE | COVERED — renewsViaRefreshAuthNotASecondAuth() |
| AU4-7 | edge | C | LIVE | COVERED — authenticatesBeforeAnyBusinessSubscribeFrame() |
| AU5-1 | both | S | LIVE | COVERED — badCredentialIs401AuthenticatedButUnauthorizedIs403() |
| AU5-2 | both | both | NO | COVERED — authenticatorUnavailableIs503RetryableWhileBadCredentialIs401Reauth() |
| AU5-3 | both | both | LIVE | COVERED — aRejectionNeverEchoesTheCredentialOnEitherPlane() |
| AU5-4 | both | C | LIVE | COVERED — forbiddenIsTerminalAndUnauthenticatedDoesNotHotLoop() |
| AU5-5 | both | S | NO | SKIP:not-in-v1 (no (server-side audit)) |
| AU5-6 | edge | both | LIVE | COVERED — credentialExpiredIsAReconnectWithAFreshCredential() |
| AU6-1 | both | C | LIVE | COVERED — oneCredentialIsOnePrincipalOnBothPlanes() |
| AU6-2 | both | C | LIVE | COVERED — badCredentialIs401AuthenticatedButUnauthorizedIs403() |
| AU6-3 | both | C | NO | SKIP:model (no) |
| AU7-1 | both | C | LIVE | COVERED — anUnknownAuthChallengeFailsClosed() |
| AU7-2 | both | C | LIVE | COVERED — aNewServerAuthenticatorDoesNotRequireADriverChange() |
| AU7-3 | both | C | LIVE | COVERED — driverIgnoresAServerIssuedSessionAndRePresentsItsOwnCredential() |
| AU8-1..8-4 | both | C | LIVE | COVERED — edgeAuthnPrecedesAuthzPrecedesDataWithZeroDataOnAnyTerminal() |
| D1-1_D1-2 | HTTP | C | LIVE | COVERED — addressesTheV1PrefixWithNoNegotiationHandshake() |
| D2-1 | HTTP | both | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer() |
| D2-2 | HTTP | S | LIVE | COVERED — routingIsExactForFixedEndpointsPrefixForConfigElse404() |
| D2-3 | HTTP | S | LIVE | COVERED — RealServerHttpTest, badRequest400IsPermanentNotRetried(), getPutDeleteAndAdminAgainstRealServer() |
| D2-4 | HTTP | S | LIVE | COVERED — emptyKeyIs400EmittedBeforeAuthentication() |
| D2-5_D2-5a | HTTP | C | LIVE | COVERED — bodiesAreParsedAsPlaintextAndValuesAsOpaqueBytesNeverJson() |
| D2-6 | HTTP | C | LIVE | COVERED — writesLiveOnlyOnTheHttpPlaneNotTheEdgePlane() |
| D2-7 | HTTP | C | LIVE | COVERED — readReturnsValueAndVersionFromHeaderEmptyValueIsPresent() |
| D3-1 | HTTP | S | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer(), readReturnsValueAndVersionFromHeaderEmptyValueIsPresent() |
| D3-2 | HTTP | C | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer(), readReturnsValueAndVersionFromHeaderEmptyValueIsPresent() |
| D3-2a | HTTP | C | LIVE | COVERED — xConsistencyLinearizableOnAnOrdinaryKeyIsNotAFreshnessProof() |
| D3-3 | HTTP | S | LIVE | COVERED — notFoundIsADefiniteAbsentNotAnError() |
| D3-4 | HTTP | C | LIVE | COVERED — consistencyLiteralIsExactAndScopeIsAnExactParam() |
| D3-5_D3-5a | HTTP | S/C | LIVE | COVERED — strongReadFailClosedNeverServesAStaleValue(), strongReadFreshnessIsHeaderCertifiedNotNameInferred(), strongReadKeyFailsClosedWithNoLinearizablePathWired() |
| D3-5b | HTTP | C | NO | SKIP:model (no (trust-model)) |
| D3-6 | HTTP | S/C | LIVE | COVERED — linearizableOnAnOrdinaryKeyThatCannotBeServedIsRetryableNotTerminal() |
| D3-7 | HTTP | S | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer() |
| D3-8 | HTTP | C | LIVE | COVERED — getIsSideEffectFreeAndFreelyRetried() |
| D4-1 | HTTP | S | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer() |
| D4-2 | HTTP | C | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer(), writeParsesSeqFromTheBodyNotAHeader() |
| D4-3 | HTTP | C | LIVE | COVERED — writesAreIdempotentAndSafeToRetry() |
| D4-4 | HTTP | C | LIVE | COVERED — putHonorsScopeAndOmitsItWhenGlobal() |
| D4-5 | HTTP | S | LIVE | COVERED — aclPutIsValidatedAsPolicyPreCommitAndIncompleteIsNotAnError() |
| D4-6 | HTTP | C | LIVE | COVERED — writeOutcomeStatusTableMapsEachOutcomeToItsReaction() |
| D4-7 | HTTP | C | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer(), writeParsesSeqFromTheBodyNotAHeader() |
| D4-8 | HTTP | C | LIVE | COVERED — indeterminate504RetriesToDefiniteAndSurfacesUnknownOnExhaustion() |
| D5-1..D5-5 | HTTP | C | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer() |
| D6-1..D6-5 | HTTP | C | LIVE | COVERED — RealServerHttpTest, getPutDeleteAndAdminAgainstRealServer() |
| D7-1..D7-4 | HTTP | S/C | LIVE | COVERED — consistencyLiteralIsExactAndScopeIsAnExactParam() |
| D8-1 | HTTP | S | LIVE | COVERED — serverEnforcesOnlyNonBlankAnd1024ByteKeyLengthBeforeBlank() |
| D8-2 | HTTP | C | LIVE | COVERED — serverAcceptsAKeyThatViolatesTheClientSidePathGrammar() |
| D8-3 | HTTP | S | LIVE | COVERED — serverRejectsAValueOverOneMebibyte() |
| D8-4 | HTTP | S | LIVE | COVERED — keyValidationIsPostAuthExceptTheEmptyKey() |
| D9-1 | HTTP | C | LIVE | COVERED — theClientExposesNoListOrEnumerationSurface() |
| D10-1 | HTTP | S | LIVE | COVERED — healthIsGetOnlyRealJsonAndUnauthenticated() |
| D10-2 | HTTP | S | LIVE | COVERED — metricsIsGetOnlyPrometheusBearerGatedAndExactPath() |
| D11-1 | HTTP | C | LIVE | COVERED — everyWriteCanRedirectAndAHintless503IsRetriedEvenAtN1() |
| D11-2 | HTTP | C | LIVE | COVERED — everyNamedStatusCarriesItsInlineReaction() |
| D11-3 | HTTP | C | LIVE | COVERED — replayGuardIsOffByDefaultAndStampsAFreshNoncePerAttemptWhenOn() |
| D11-4 | HTTP | C | LIVE | COVERED — failsClosedOnAnUnknownStatusAndExposesNoneOfTheNamedOmissions() |
| R2-1 | HTTP | C | LIVE | COVERED — hintIsReadFromTheHeaderNotTheBodyAndResolvedThroughTheMap() |
| R2-2 | HTTP | C | LIVE | COVERED — hintIsReadFromTheHeaderNotTheBodyAndResolvedThroughTheMap(), unresolvableHintDegradesToHintlessNeverAWireAddress() |
| R2-3 | HTTP | C | LIVE | COVERED — hintless503BacksOffAndRetriesTheSameEndpoint() |
| R2-4 | HTTP | C | LIVE | COVERED — indeterminate504RetriesToDefiniteAndSurfacesUnknownOnExhaustion() |
| R3-1 | HTTP | C | NO | SKIP:operator (no (operator config)) |
| R3-2 | HTTP | C | LIVE | COVERED — thereIsNoTopologyOrMembershipDiscoveryEndpoint() |
| R3-3 | HTTP | C | NO | SKIP:model (no (client-internal)) |
| R4-1_R4-2 | HTTP | C | LIVE | COVERED — leaderFollowingIsRequiredEvenAtN1AndTheHintlessLoopIsBounded() |
| R4-3 | HTTP | C | NO | SKIP:model (no (client-internal)) |
| R4-4 | HTTP | C | LIVE | COVERED — anyReadCan503BecauseTheStrongReadClassIsServerSideInvisible() |
| R5-1..R5-4 | HTTP | C | LIVE | COVERED — aHintIsPerRequestAndIsNeverCachedAcrossKeys(), noClientSideShardingTheFullKeyGoesInThePathWithNoShardOrNParameter() |
| R6-1 | HTTP | C | LIVE | COVERED — hintless503BacksOffAndRetriesTheSameEndpoint() |
| R6-2 | HTTP | C | LIVE | COVERED — writesAreIdempotentAndSafeToRetry() |
| R6-3 | HTTP | C | NO | SKIP:model (no (client-internal)) |
| R6-4 | HTTP | C | LIVE | COVERED — replayGuard409RetriesWithAFreshNonce() |
| R7-1_R7-2 | HTTP | C | LIVE | COVERED — failsClosedOnAn3xxRedirectAndOnARicherWireSuppliedAddress() |
| R8-1 | HTTP | C/operator | NO | SKIP:operator (no (operator)) |
| R8-2 | HTTP | operator | NO | SKIP:model (no) |
| R8-3 | HTTP | C | PARTIAL | SKIP:model (partial (client-side)) |
| R8-4 | HTTP | S | LIVE | COVERED — theLeaderHintIsAuthorizationGatedNoAnonymousTopologyDisclosure() |
| F2-1 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F2-2 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F2-3 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F2-4 | edge | C | NO | SKIP:model (no (trust-model)) |
| F3-1 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F3-2 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F4-1 | edge | both | LIVE | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F4-2 | edge | C | LIVE | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F4-3 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F5-1 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F5-2 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F5-3 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-1 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-1a | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-2_F6-2a | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-3 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-4..F6-6 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-7 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-8_F6-8a | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6-9 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6A-1..F6A-2 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6A-3 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6A-4 | edge | both | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F6A-5 | edge | S | LIVE | COVERED — anOverCapCredentialIsRejectedBeforeVerificationWithZeroData() |
| F7-1 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F7-2 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F8-1_F8-2 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F9-1 | edge | C | PARTIAL | SKIP:model (partial (client-side)) |
| F9-2 | edge | C | LIVE | COVERED — mtlsHandshakeUsesTheTlsV13AeadProfile() |
| F9-3 | edge | C | LIVE | COVERED — mtlsCertDnIsAuthoritativeOverAnAdvisoryEdgeId() |
| F9-4 | edge | C | PARTIAL | SKIP:model (partial (client-side)) |
| F10-1 | edge | C | LIVE | COVERED — reconnectMintsAFreshWatchIdKeepingOnlyTheCursor() |
| F10-1a | edge | C | LIVE | COVERED — reconnectMintsAFreshWatchIdKeepingOnlyTheCursor() |
| F10-1b | edge | C | LIVE | COVERED — cursoredWatchCannotShareAConnection() |
| F10-1c | edge | C | LIVE | COVERED — clientHandlesASnapshotFirstReBootstrap() |
| F10-1d | edge | C | LIVE | COVERED — firstRoutedFrameIsSentEagerlyOnConnectNotIdled() |
| F10-1e | edge | C | LIVE | COVERED — tokenEdgeSendsAuthBeforeAnyBusinessFrame() |
| F10-2 | edge | C | LIVE | COVERED — silentCloseIsRetryableAndDistinctFromAFrameBearingReject() |
| F10-2a | edge | operator | NO | SKIP:model (no) |
| F10-3 | edge | C | LIVE | COVERED — demotedToCatchupIsNonFatalTheConnectionSurvives(), quarantinedIsAConnectionFatalTeardown(), watchEmitsCursorAckAsMandatoryFlowControl() |
| F10-4 | edge | C | LIVE | COVERED — quarantinedTriggersOwnBoundedBackoffNotAReconnectStorm() |
| F11-1 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F11-2 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F11-3 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| F13-1..F13-9 | Raft | (non-driver) | NO | SKIP:not-in-v1 (the §13 Raft plane is a non-driver surface, explicitly out of driver-protocol conformance scope — catalog §7.11 / the F13 catalog row marks it "not independently re-verified" in the driver suite; documented for completeness only) |
| E2-1 | HTTP | C | LIVE | COVERED — theCompleteHttpStatusTableMapsToTheRequiredReaction() |
| E2-2 | HTTP | C | LIVE | COVERED — strongReadFailClosed503NeverServesStale() |
| E3-1 | edge | C | CODEC | COVERED — WireConformanceRatchetTest, everyWireCaseMatchesTheManifest() |
| E3-2 | edge | C | LIVE | COVERED — theCatchUpLadderDemotedIsNonFatalQuarantinedEndsTheSession() |
| E3-3 | edge | C | LIVE | COVERED — aWatchCanceledNotAuthorizedSurfacesForbiddenPerWatchAgainstTheRealClient(), reactionScopeIsCodePlusCarrierNotCodeAlone() |
| E4-1 | both | C | LIVE | COVERED — aWatchCanceledNotAuthorizedSurfacesForbiddenPerWatchAgainstTheRealClient(), the401Vs403SplitIsAuthenticationVsAuthorization(), the401Vs403SplitOnTheEdgeMirrorsTheHttpPlane() |
| E5-1 | both | C | NO | SKIP:model (no (client design)) |
| E6-1 | both | C | LIVE | COVERED — branchesOnTheStatusCodeNotABodyThatLooksLikeJson() |
| E7-1 | both | C | LIVE | COVERED — everyOutcomeFallsIntoTheCorrectRetryClass() |
