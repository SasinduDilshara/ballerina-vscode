/**
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import styled from "@emotion/styled";
import { useMutation, useQuery } from "@tanstack/react-query";
import { VSCodeButton } from "@vscode/webview-ui-toolkit/react";
import { AIMachineEventType } from "@wso2/ballerina-core";
import { useRpcContext } from "@wso2/ballerina-rpc-client";
import { AnthropicIcon, Icon, Typography } from "@wso2/ui-toolkit";
import React from "react";
import { Banner } from "../../../components/Banner";
import { AwsLogo, GoogleLogo } from "./ProviderLogos";

const TERMS_OF_USE_URL = "https://wso2.com/licenses/wso2-ai-services-terms-of-use/";
const DATA_HANDLING_URL = "https://wso2.com/privacy-policy/";

const PanelWrapper = styled.div`
    display: flex;
    flex-direction: column;
    height: 100vh;
    overflow-y: auto;
    padding: 24px 16px;
`;

const TopSpacer = styled.div`
    flex-grow: 1;
    min-height: 24px;
`;

const BottomSpacer = styled.div`
    height: 28px;
`;

const EndSpacer = styled.div`
    flex-grow: 1;
    min-height: 24px;
`;

const HeaderContent = styled.div`
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
`;

const ContentSection = styled.div`
    display: flex;
    flex-direction: column;
    gap: 20px;
    width: 100%;
    max-width: 360px;
    align-self: center;
`;

const Title = styled.h2`
    display: inline-flex;
    margin-top: 40px;
    margin-bottom: 8px;
`;

const PrimaryLoginSection = styled.div`
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
`;

const StyledButton = styled(VSCodeButton)`
    width: 100%;
    height: 36px;
`;

const RecommendedBanner = styled.div`
    display: flex;
    align-items: center;
    justify-content: center;
    flex-wrap: wrap;
    gap: 6px;
    width: 100%;
    padding: 6px 12px;
    box-sizing: border-box;
    border-radius: 4px;
    font-size: 12px;
    color: var(--vscode-textLink-foreground);
    background-color: var(--vscode-textBlockQuote-background, var(--vscode-editorWidget-background));
    border: 1px solid var(--vscode-widget-border, transparent);
`;

const InstallingContainer = styled.div`
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 10px;
    color: var(--vscode-descriptionForeground);
    font-size: 13px;
`;

const Divider = styled.div`
    display: flex;
    align-items: center;
    color: var(--vscode-descriptionForeground);
    font-size: 12px;
    width: 100%;
    &::before,
    &::after {
        content: "";
        flex: 1;
        border-bottom: 1px solid var(--vscode-widget-border);
        margin: 0 8px;
    }
`;

const ProviderList = styled.div`
    display: flex;
    flex-direction: column;
    gap: 8px;
`;

const ProviderCard = styled.button`
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    padding: 10px 12px;
    box-sizing: border-box;
    background: none;
    text-align: left;
    cursor: pointer;
    border: 1px solid var(--vscode-widget-border, var(--vscode-editorWidget-border));
    border-radius: 6px;
    color: var(--vscode-foreground);
    transition: background-color 0.15s ease, border-color 0.15s ease;
    &:hover {
        background-color: var(--vscode-list-hoverBackground);
        border-color: var(--vscode-focusBorder);
    }
    &:focus-visible {
        outline: 1px solid var(--vscode-focusBorder);
        outline-offset: 1px;
    }
`;

const ProviderLogoWrapper = styled.div`
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    width: 32px;
    height: 32px;
`;

const ProviderText = styled.div`
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    gap: 2px;
`;

const ProviderTitle = styled.span`
    font-size: 13px;
    font-weight: 600;
`;

const ProviderDescription = styled.span`
    font-size: 12px;
    color: var(--vscode-descriptionForeground);
`;

const FooterContent = styled.div`
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 12px;
    width: 100%;
    max-width: 360px;
    align-self: center;
    margin-top: 24px;
`;

const Notice = styled.div`
    display: flex;
    align-items: flex-start;
    gap: 8px;
    font-size: 12px;
    color: var(--vscode-descriptionForeground);
    text-align: left;
`;

const FooterLinks = styled.div`
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 12px;
    flex-wrap: wrap;
`;

const FooterSeparator = styled.span`
    color: var(--vscode-widget-border);
`;

const FooterLink = styled.a`
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--vscode-textLink-foreground);
    text-decoration: none;
    cursor: pointer;
    &:hover {
        text-decoration: underline;
    }
`;

interface ProviderOption {
    key: string;
    title: string;
    description: string;
    logo: React.ReactNode;
    onClick: () => void;
}

const LoginPanel: React.FC = () => {
    const { rpcClient } = useRpcContext();

    const { data: isPlatformAvailable, refetch: refetchPlatformAvailability } = useQuery({
        queryKey: ["platform-availability"],
        queryFn: () => rpcClient.getAiPanelRpcClient().isPlatformExtensionAvailable(),
    });

    const {
        mutate: installExtension,
        isPending: isInstallingExtension,
        error: installExtensionError,
    } = useMutation({
        mutationFn: async () => {
            return rpcClient.getCommonRpcClient().executeCommand({
                commands: ["workbench.extensions.installExtension", "wso2.wso2-integrator"],
            });
        },
        onSettled: () => refetchPlatformAvailability(),
    });

    const handleCopilotLogin = () => {
        rpcClient.sendAIStateEvent(AIMachineEventType.LOGIN);
    };

    const handleAnthropicKeyClick = () => {
        rpcClient.sendAIStateEvent(AIMachineEventType.AUTH_WITH_API_KEY);
    };

    const handleAwsBedrockClick = () => {
        rpcClient.sendAIStateEvent(AIMachineEventType.AUTH_WITH_AWS_BEDROCK);
    };

    const handleVertexAiClick = () => {
        rpcClient.sendAIStateEvent(AIMachineEventType.AUTH_WITH_VERTEX_AI);
    };

    const providerOptions: ProviderOption[] = [
        {
            key: "anthropic",
            title: "Anthropic API Key",
            description: "Use your Anthropic API key to power Copilot",
            logo: <AnthropicIcon size={26} />,
            onClick: handleAnthropicKeyClick,
        },
        {
            key: "aws-bedrock",
            title: "AWS Bedrock",
            description: "Use your AWS Bedrock account",
            logo: <AwsLogo />,
            onClick: handleAwsBedrockClick,
        },
        {
            key: "vertex-ai",
            title: "Google Vertex AI",
            description: "Use your Google Vertex AI account",
            logo: <GoogleLogo />,
            onClick: handleVertexAiClick,
        },
    ];

    return (
        <PanelWrapper>
            <TopSpacer />
            <HeaderContent>
                <Icon
                    name="bi-ai-chat"
                    sx={{ width: 54, height: 54 }}
                    iconSx={{ fontSize: "54px", color: "var(--vscode-foreground)", cursor: "default" }}
                />
                <Title>Welcome to WSO2 Integrator Copilot</Title>
                <Typography
                    variant="body1"
                    sx={{
                        color: "var(--vscode-descriptionForeground)",
                        textAlign: "center",
                        maxWidth: 350,
                        fontSize: 14,
                    }}
                >
                    Your AI pair programmer for integration development.
                </Typography>
            </HeaderContent>
            <BottomSpacer />
            <ContentSection>
                {isPlatformAvailable ? (
                    <PrimaryLoginSection>
                        <StyledButton onClick={handleCopilotLogin}>
                            Login using WSO2 Integration Platform
                        </StyledButton>
                        <RecommendedBanner>
                            <Icon
                                isCodicon
                                name="star-full"
                                iconSx={{ fontSize: "13px", color: "var(--vscode-textLink-foreground)" }}
                                sx={{ height: 13, width: 13 }}
                            />
                            Recommended &middot; Managed authentication &middot; No API keys required
                        </RecommendedBanner>
                    </PrimaryLoginSection>
                ) : (
                    <InstallingContainer>
                        Install WSO2 Integrator to sign in with the WSO2 Integration Platform and use BI Copilot.
                        <StyledButton
                            disabled={isInstallingExtension || undefined}
                            onClick={installExtension}
                            appearance="secondary"
                        >
                            Install WSO2 Integrator
                        </StyledButton>
                        {installExtensionError && (
                            <Banner
                                variant="error"
                                message={installExtensionError?.message || "Failed to install WSO2 Integrator"}
                            />
                        )}
                    </InstallingContainer>
                )}

                <Divider>Use your own AI provider</Divider>

                <ProviderList>
                    {providerOptions.map((option) => (
                        <ProviderCard key={option.key} onClick={option.onClick}>
                            <ProviderLogoWrapper>{option.logo}</ProviderLogoWrapper>
                            <ProviderText>
                                <ProviderTitle>{option.title}</ProviderTitle>
                                <ProviderDescription>{option.description}</ProviderDescription>
                            </ProviderText>
                            <Icon
                                isCodicon
                                name="chevron-right"
                                iconSx={{ fontSize: "16px", color: "var(--vscode-descriptionForeground)" }}
                                sx={{ height: 16, width: 16 }}
                            />
                        </ProviderCard>
                    ))}
                </ProviderList>
            </ContentSection>
            <EndSpacer />
            <FooterContent>
                <Notice>
                    <Icon
                        isCodicon
                        name="info"
                        iconSx={{ fontSize: "14px", color: "var(--vscode-descriptionForeground)" }}
                        sx={{ height: 14, width: 14, marginTop: "1px" }}
                    />
                    WSO2 Integrator Copilot uses AI to assist with integration. AI-generated content may contain
                    mistakes. Always review generated changes.
                </Notice>
                <FooterLinks>
                    <FooterLink href={TERMS_OF_USE_URL} target="_blank" rel="noopener noreferrer">
                        <Icon
                            isCodicon
                            name="law"
                            iconSx={{ fontSize: "14px", color: "var(--vscode-textLink-foreground)" }}
                            sx={{ height: 14, width: 14 }}
                        />
                        Terms of Use
                    </FooterLink>
                    <FooterSeparator>|</FooterSeparator>
                    <FooterLink href={DATA_HANDLING_URL} target="_blank" rel="noopener noreferrer">
                        <Icon
                            isCodicon
                            name="shield"
                            iconSx={{ fontSize: "14px", color: "var(--vscode-textLink-foreground)" }}
                            sx={{ height: 14, width: 14 }}
                        />
                        Data Handling &amp; Privacy
                    </FooterLink>
                </FooterLinks>
            </FooterContent>
        </PanelWrapper>
    );
};

export default LoginPanel;
