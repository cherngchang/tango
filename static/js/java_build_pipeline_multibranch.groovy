import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String baseImageJava = "openjdk/openjdk-11-rhel7:1.1-4",
        String baseImageTomcat = "jboss-webserver-5/webserver52-openjdk11-tomcat9-openshift-rhel7:1.0-5.1575996526",
        String baseImageJboss = "jboss-eap-7/eap72-openjdk11-openshift-rhel8:1.2-5"
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
    assert dockerRegistryURL?.trim(): "dockerRegistryURL is required."
    assert env.OPENSHIFT_APP_PROJECTS?.trim(): "Env OPENSHIFT_APP_PROJECTS is required"

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def LOG_LEVEL = 2
    def POM_RELEASE = env.BRANCH_NAME
    def POM = ''
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    def pomQualityCheckOverrideProperties = [
            'cpd.skip',
            'checkstyle.skip',
            'pmd.skip',
            'skip.pmd.check',
            'maven.test.skip',
    ]

    BuildConfiguration[] buildConfigurations = [
            new BuildConfiguration(id: "java", baseImage: env.BASE_IMAGE_JAVA ?: baseImageJava, buildOption: env.BUILD_OPTION_JAVA ?: "", packagingType: "jar|pom"),
            new BuildConfiguration(id: "tomcat", baseImage: env.BASE_IMAGE_TOMCAT ?: baseImageTomcat, buildOption: env.BUILD_OPTION_TOMCAT ?: "-Ddocker.containerizing.mode=exploded", packagingType: "war|pom"),
            new BuildConfiguration(id: "jboss", baseImage: env.BASE_IMAGE_JBOSS ?: baseImageJboss, buildOption: env.BUILD_OPTION_JBOSS ?: "-Djib.container.appRoot=/opt/eap/standalone/deployments", packagingType: ".*")
    ] as BuildConfiguration[]
    //This variable used for  setting parameters "No Deployment" choice and verify user choose "No Deployment"
    NO_DEPLOYMENT = 'No Deployment'
    CLUSTER_CONFIG_FILE = 'com/mufg/tsi/devsecops/pipelines/openshift.yaml'
    //This flag is used to carry deployment status true: deploy, false: no deploy
    DEPLOYMENT_FLAG = true
    VERACODE_SCRIPTS = 'com/mufg/tsi/devsecops/pipelines/bin/vcode_helper.sh'
    EMERGENCY_CM_APPROVERS_FILE = 'change_management/emergency_approvers.yaml'
    CUCUMBER_REPORT = 1

    final Map<String, Map<String, String>> CLUSTER_MAP = new HashMap<>()

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-agent-pod-multibranch-${openshiftProject}-${BUILD_NUMBER}"
                yaml libraryResource("agent-pod-conf/jenkins-agent-pod-multibranch.yaml")
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 180, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY = 'http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY = "${env.PROXY}"
            HTTPS_PROXY = "${env.PROXY}"
            // This is a hack to force connectivity to these cluster to go direct
            NO_PROXY = "opn-console-mmz-uat1.unionbank.com,opn-console-mmz-uat2.opn.unionbank.com,.opn.unionbank.com"
        }

        parameters {
            choice(name: "Deployment", choices: "${fetchClustersFromOnboardingFile(CLUSTER_MAP)}", description: "Choose the environment to deploy.")
            string(name: 'ChangeNumber', defaultValue: '', description: 'Change Management Ticket Number - e.g. CHG1234567. This must be provided for production deployment or the deployment will be denied. For emergency deployment, i.e. in case an emergency change management tickat can\'t be obtained, enter \"emergency\" and reach out to the pipeline operations team for approval.')
            string(name: 'CucumberTags', defaultValue: '', description: 'Cucumber tags for testing such as @SIT,@CIF separated with comma')
            string(name: 'testsToRun', defaultValue: '', description: 'ALM Octane Test Parameters')
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                        // determine deployment is required when parameterizing
                        //set DEPLOYMENT_FLAG = false when deployment should be stoped
                        if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)) {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment:  Branch chosen ${env.BRANCH_NAME} is not in defined the allowed list ${DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX} or TAG_NAME is not defined ${env.TAG_NAME}"
                        }

                        if ("${NO_DEPLOYMENT}" == "${params.Deployment}" || "${params.Deployment}" == "") {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment build was chosen"
                        }

                        if (!env.K8S_DEPLOYMENT_CONFIG || env.K8S_DEPLOYMENT_CONFIG?.trim() == "" || !env.K8S_DEPLOYMENT_REPOSITORY || env.K8S_DEPLOYMENT_REPOSITORY?.trim() == "") {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment configuration"
                        }
                    }
                }
            }
            stage('Validate POM File') {
                steps {
                    container('maven') {
                        script {
                            POM = readMavenPom file: 'pom.xml'
                            assert POM.properties['gsi-code']: "Invalid pom.xml - See details at ${USER_DOC}"
                            def pomVersion = POM.version
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                if (pomVersion.startsWith('${revision}')) {
                                    pomVersion = POM.properties['revision']
                                }
                                echo "Release pom version: ${pomVersion}"
                            } else {
                                echo "Non release pom version: ${POM.version} ${env.BRANCH_NAME}"
                                if (env.TAG_NAME) {
                                    pomVersion = env.BRANCH_NAME
                                }
                            }
                            env.POM_VERSION = pomVersion.replaceAll("-SNAPSHOT", "") + "${escapeBranch()}"

                            echo "pom version original: ${POM.version} "
                            echo "branch ${escapeBranch()}"
                            echo "Build Number: ${env.BUILD_NUMBER}"

                            try {
                                for (property in pomQualityCheckOverrideProperties) {
                                    overrideValue = POM.properties[property];
                                    if (overrideValue != null) {
                                        echo "${property}: ${overrideValue}";
                                        assert !overrideValue.toBoolean();
                                    }
                                }
                                assert POM.properties['maven.pmd.maxAllowedViolations'] == null
                            } catch (AssertionError e) {
                                echo "Invalid POM Detected - Evil No Bad – Don’t turn off quality checks, this is against Bank Procedures - See details at ${USER_DOC}"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                        }
                        echo "POM_VERSION: ${env.POM_VERSION}"
                    }
                }
            }

            stage('Build Binaries') {
                when {
                    not { buildingTag() }
                }
                steps {
                    script {
                        container('maven') {
                            def cucumberTag = "${params.CucumberTags}" == "" ? '' : "-Dcucumber.options=\"--tags ${params.CucumberTags}\""
                            def testsToRun = params.testsToRun ?: ''
                            def buildCommand = "mvn clean install ${cucumberTag}"
                            if (testsToRun != "") {
                                echo "test to run: ${testsToRun}"
                                convertTestsToRun format: '', framework: 'cucumber_jvm'
                                buildCommand = buildCommand + "-Dfeatures=\"$testsToRunConverted\""
                            }
                            sh buildCommand
                            CUCUMBER_REPORT = sh(script: "find ${WORKSPACE} -name *cucumber*.json | grep .", returnStatus: true)
                        }
                    }
                }
            }

            stage('Nexus IQ Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('maven') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                nexusIQScan(
                                        IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? (("${env.BRANCH_NAME}" == "develop") ? 'build' : 'release') : 'build',
                                        GSI: POM.properties['gsi-code'],
                                        ARTIFACT_ID: POM.artifactId
                                )
                            }
                        }
                    }
                }
            }

            stage('Veracode Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('maven') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                veracodeScan(
                                        buildType: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? (("${env.BRANCH_NAME}" == "develop") ? 'snapshot' : 'release') : 'snapshot',
                                        gsiCode: POM.properties['gsi-code']
                                )
                            }
                        }
                    }
                }
            }

            stage('Publish Artifacts') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        not { buildingTag() }
                    }
                }
                environment {
                    GIT_AUTH = credentials('bitbucket-tagging')
                }
                steps {
                    script {
                        container('maven') {
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                try {
                                    sh "mvn versions:set -DnewVersion=${env.POM_VERSION}"

                                    POM_RELEASE = "v${env.POM_VERSION}"
                                    //Assuming the parent pom is always enterprise-tomcat/java/jboss-pom with buildType between - and -
                                    def buildTypes
                                    if (POM.parent != null && POM.parent.artifactId != null) {
                                        buildTypes = "${POM.parent.artifactId}".split('-')
                                    } else {
                                        buildTypes = []
                                    }
                                    def buildConfiguration = buildConfigurations.find { it.id == (buildTypes.size() > 1 ? buildTypes[1] : "java") } ?: buildConfigurations[0]
                                    assert (POM.packaging =~ /${buildConfiguration.packagingType}/): "Project packaging mismatches base image. Jar for spring boot, war for tomcat and EAP for any. ${buildConfiguration.packagingType}"
                                    def buildCommandLine = "${buildConfiguration.buildOption} -Ddocker.image.tag=${POM_RELEASE} -Dbase.image=${buildConfiguration.baseImage}"
                                    def buildProfile = env.BUILD_PROFILE ?: 'docker'

                                    echo "Build Command Line: ${buildCommandLine}"
                                    echo "Build Profile: ${buildProfile}"

                                    //skip testing in deployment phase

                                    sh "mvn deploy -P ${buildProfile} ${buildCommandLine} -DskipTests=true"

                                    //Tagging
                                    //String NEXUS = "${dockerRegistryURL}".split(':')[0]
                                    //env.NEXUS_URL = "https://${NEXUS}"
                                    //String deploymentName = params.Deployment
                                    //Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    //String environment = clusterEnv.get("environment")
                                    //tagAssociateAPI(environment, env.POM_VERSION, POM.artifactId)

                                    sh "git tag ${POM_RELEASE} -am \"Jenkins release tag\""
                                    sh('''
                                    git config --local credential.helper "!f() { echo username=\\$GIT_AUTH_USR; echo password=\\$GIT_AUTH_PSW; }; f"
                                    git push origin --tags
                                ''')
                                } catch (error) {
                                    echo "Exception in deploy: " + error.getMessage()
                                    throw error
                                }
                            } else {
                                echo "Code is not in a deploy branch. Bypassing deployment build."
                            }
                        }
                    }
                }
            }

            stage('Change Management Validation') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                    }
                }
                steps {
                    container('python3') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deplolyment cluster: ${params.Deployment}"
                                String deploymentName = params.Deployment
                                Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                String environment = clusterEnv.get("environment")
                                String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                String project = clusterEnv.get("project")
                                String cluster = clusterEnv.get("cluster")

                                if (Helper.isCMRequired(environment)) {
                                    // if ticket number = emergency, allow for emergency deployment by the pipeline operations team
                                    if (params.ChangeNumber.trim().toLowerCase() == "emergency") {
                                        echo "########## IMPORTANT ##########"
                                        echo "This is an emergency deployment to Production. Explicit approval by the pipeline operations team is required. Please have the operations team work alongside you to approve the deployment."
                                        echo "Emergency deployment should be a last resort to resolve a pressing Production outage in case an emergency change management ticket can't be obtained. A post-event change management ticket must be raised to cover this emergency change."
                                        def userInput = ''
                                        timeout(time: 15, unit: "MINUTES") {
                                            userInput = input(message: 'Pipeline Operator: Do you approve this emergency deployment to Production?',
                                                    ok: 'Approve',
                                                    submitter: getEmergencyCMApprovers(),
                                                    parameters: [
                                                            [$class      : 'TextParameterDefinition',
                                                             defaultValue: '',
                                                             description : 'Enter "Approved" literally to approve or the deployment will be aborted.',
                                                             name        : '']
                                                    ])
                                        }
                                        if (userInput.trim().toLowerCase() != "approved") {
                                            error "Invalid value for approving emergency deployment. Deployment aborted."
                                        }
                                        echo "########## IMPORTANT ##########"
                                        echo "Emergency deployment approved. Proceed to deployment."
                                    } else {
                                        echo "This is a Production Deployment. Validate Change Ticket with CM (ServiceNow)."
                                        try {
                                            validateChangeManagementTicket(
                                                    gsiCode: "${env.GSI}",
                                                    changeNumber: "${params.ChangeNumber}"
                                            )
                                            //TO-DO - validate cluster/project with labels matching environment
                                        } catch (e) {
                                            //currentBuild.result = "FAILURE"
                                            error "Change Management Validation Failed. See logs for more details.\n${e}"
                                        }
                                    }
                                } else {
                                    echo "Change Management is not required for ${environment} environment."
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy to Target Environment') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        expression { DEPLOYMENT_FLAG }
                    }
                }
                steps {
                    container('jnlp') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deplolyment clusters: ${params.Deployment}"
                                if (env.K8S_DEPLOYMENT_REPOSITORY) {
                                    //assuming deployment repo has the same branch name as environment name except prd for master
                                    assert env.K8S_DEPLOYMENT_CONFIG: "K8S_DEPLOYMENT_CONFIG is required to deploy. Assuming the IMAGE_VERSION will be replaced with current build version"
                                    String deploymentName = params.Deployment
                                    Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    String branchName = clusterEnv.get("environment")
                                    branchName = "prd".equalsIgnoreCase(branchName) ? "master" : branchName
                                    dir('deployment-repo') {
                                        git branch: branchName, url: env.K8S_DEPLOYMENT_REPOSITORY, credentialsId: 'bitbucket'
                                        try {
                                            String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                            String project = clusterEnv.get("project")
                                            String cluster = clusterEnv.get("cluster")
                                            String tokenId = cluster + '-' + project + '-jenkins'
                                            String imageTagName = env.IMAGE_VERSION_TAG ?: 'IMAGE_VERSION'

                                            echo "Deployment Name: ${deploymentName}"
                                            echo "clusterEnv: ${clusterEnv}"
                                            echo "Cluster Map: ${CLUSTER_MAP}"
                                            echo "Cluster URL: ${clusterURL}"
                                            echo "TOKEN ID: ${tokenId}"
                                            echo "image version tag: ${imageTagName}"

                                            sh "find . -type f -exec sed -i s/${imageTagName}/${POM_RELEASE}/g {} \\;"
                                            withCredentials([string(credentialsId: "${tokenId}", variable: 'TOKEN')]) {
                                                openshift.logLevel(LOG_LEVEL)
                                                applyFile(
                                                        projectName: project,
                                                        deploymentFile: "${env.K8S_DEPLOYMENT_CONFIG}",
                                                        clusterAPI: clusterURL,
                                                        clusterToken: "${TOKEN}"
                                                )
                                            }

                                            //Tagging
                                            //String NEXUS = "${dockerRegistryURL}".split(':')[0]
                                            //env.NEXUS_URL = "https://${NEXUS}"
                                            //String environment = clusterEnv.get("environment")
                                            //tagAssociateAPI(environment, env.POM_VERSION, POM.artifactId)

                                            if (Helper.isCMRequired(clusterEnv.get("environment"))) {
                                                // Preserve build for successful Production deployment
                                                currentBuild.keepLog = true
                                            }
                                        } catch (e) {
                                            echo "Error encountered: " + e.getMessage()
                                            throw e
                                        }
                                    }
                                } else {
                                    echo "No deployment repo is specified! No deployment."
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    echo "cucumber report ${CUCUMBER_REPORT}"
                    if (CUCUMBER_REPORT == 0) {
                        cucumber '**/*cucumber*.json'
                        publishGherkinResults ''
                    }
                }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchClustersFromOnboardingFile(Map<String, Map<String, String>> clustersMap) {
    List<Map<String, String>> clustersData = Helper.base64DecodeMap(env.OPENSHIFT_APP_PROJECTS)
    echo "OPENSHIFT_APP_PROJECTS: ${clustersData}"
    StringBuffer clusterNames = new StringBuffer()
    for (Map<String, String> map : clustersData) {
        StringBuffer individualName = new StringBuffer()
        for (Map.Entry<String, String> entry : map.entrySet()) {
            individualName.append(entry.getKey()).append(":").append(entry.getValue()).append(" ")
        }
        clustersMap.put(individualName.toString().trim(), map)
        clusterNames.append(individualName.toString()).append("\n")
    }
    clusterNames.append("${NO_DEPLOYMENT}")
    return clusterNames.toString()
}

def getEnvUrl(String envName) {
    def data = libraryResource "${CLUSTER_CONFIG_FILE}"
    return Helper.lookupClusterURL(data, envName)
}

def getEmergencyCMApprovers() {
    def data = libraryResource "${EMERGENCY_CM_APPROVERS_FILE}"
    return Helper.getEmergencyCmApprovers(data)
}
