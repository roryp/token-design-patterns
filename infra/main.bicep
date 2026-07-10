targetScope = 'subscription'

@minLength(1)
@maxLength(64)
param environmentName string

@description('Primary Azure region for all resources.')
param location string = 'eastus2'

@description('Resource group created by this deployment.')
param resourceGroupName string = 'rg-tokenflow-${environmentName}'

@description('Object ID of the signed-in deployment principal for optional model testing.')
param principalId string = ''

@description('GPT-5.6 model version.')
param modelVersion string = '2026-07-09'

@description('Azure OpenAI deployment SKU.')
@allowed([
  'GlobalStandard'
  'DataZoneStandard'
])
param modelSku string = 'GlobalStandard'

@description('Capacity assigned to each GPT-5.6 deployment in thousands of tokens per minute.')
@minValue(1)
param modelCapacity int = 10

var resourceToken = toLower(uniqueString(subscription().id, environmentName, location))
var tags = {
  'azd-env-name': environmentName
  application: 'tokenflow-lab'
  'managed-by': 'azd'
}

resource resourceGroup 'Microsoft.Resources/resourceGroups@2024-11-01' = {
  name: resourceGroupName
  location: location
  tags: tags
}

module resources './resources.bicep' = {
  name: 'tokenflow-resources'
  scope: resourceGroup
  params: {
    environmentName: environmentName
    location: location
    resourceToken: resourceToken
    principalId: principalId
    modelVersion: modelVersion
    modelSku: modelSku
    modelCapacity: modelCapacity
    tags: tags
  }
}

output AZURE_RESOURCE_GROUP string = resourceGroup.name
output AZURE_LOCATION string = location
output AZURE_CONTAINER_APP_NAME string = resources.outputs.containerAppName
output AZURE_CONTAINER_APP_URL string = resources.outputs.containerAppUrl
output AZURE_CONTAINER_REGISTRY_ENDPOINT string = resources.outputs.containerRegistryEndpoint
output AZURE_OPENAI_ENDPOINT string = resources.outputs.openAiEndpoint
output AZURE_OPENAI_ACCOUNT_NAME string = resources.outputs.openAiAccountName
output TOKEN_PATTERNS_SMALL_MODEL string = resources.outputs.smallModelDeployment
output TOKEN_PATTERNS_MEDIUM_MODEL string = resources.outputs.mediumModelDeployment
output TOKEN_PATTERNS_LARGE_MODEL string = resources.outputs.largeModelDeployment
