package com.myorg;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.LambdaRestApi;
import software.amazon.awscdk.services.apigateway.Resource;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;

import java.util.List;
import java.util.Map;

public class MinecraftServerStack extends Stack {
    public MinecraftServerStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public MinecraftServerStack(final Construct scope, final String id, final StackProps props) {

        super(scope, id, props);
        String userData = """
#!/bin/bash
        set -e
        
        export DEBIAN_FRONTEND=noninteractive
        
        # 1. Update system and install dependencies
        apt-get update
        apt-get upgrade -y -o Dpkg::Options::="--force-confold"
        apt-get install -y openjdk-21-jre-headless wget curl jq
        
        # 2. Create minecraft user
        useradd -m -r -d /opt/minecraft -s /bin/bash minecraft
        
        # 3. Create server directory
        mkdir -p /opt/minecraft/server
        cd /opt/minecraft/server
        
        # 4. Fetch the latest Vanilla Minecraft server jar via Mojang API
        echo "Fetching latest Minecraft server version..."
        VERSION=$(curl -s https://launchermeta.mojang.com/mc/game/version_manifest.json | jq -r '.latest.release')
        VERSION_URL=$(curl -s https://launchermeta.mojang.com/mc/game/version_manifest.json | jq -r --arg VERSION "$VERSION" '.versions[] | select(.id==$VERSION) | .url')
        SERVER_URL=$(curl -s $VERSION_URL | jq -r '.downloads.server.url')
        
        echo "Downloading Minecraft $VERSION server jar..."
        wget -O server.jar $SERVER_URL
        
        # 5. Accept EULA automatically
        echo "eula=true" > eula.txt
        
        # 6. Set ownership
        chown -R minecraft:minecraft /opt/minecraft
        
        # 7. Create systemd service
        cat > /etc/systemd/system/minecraft.service << 'SERVICEEOF'
        [Unit]
        Description=Minecraft Dedicated Server
        After=network.target

        [Service]
        Type=simple
        User=minecraft
        WorkingDirectory=/opt/minecraft/server
        # Adjust JVM memory arguments (-Xmx and -Xms) as needed based on instance type
        ExecStart=/usr/bin/java -Xmx4G -Xms4G -jar server.jar nogui
        Restart=on-failure
        RestartSec=10
        StandardOutput=journal
        StandardError=journal
   
        [Install]
        WantedBy=multi-user.target
SERVICEEOF
    
        # 8. Enable and start the service
        systemctl daemon-reload
        systemctl enable minecraft.service
        systemctl start minecraft.service
    
        echo "Minecraft server installation complete!"
    """;

        // Create VPC with NAT Gateway for private subnet egress
        Vpc vpc = Vpc.Builder.create(this, "MinecraftVpc")
                .maxAzs(1)
                .natGateways(0)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("Public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build()
                ))
                .build();

        // Security Group
        SecurityGroup sg = SecurityGroup.Builder.create(this, "MinecraftSG")
                .vpc(vpc)
                .description("Security group for Minecraft game server")
                .allowAllOutbound(true)
                .build();

        // Game port
        sg.addIngressRule(Peer.anyIpv4(), Port.tcp(25565), "Minecraft Game Port");
        // Optional: SSH access (comment out if not needed)
        sg.addIngressRule(Peer.anyIpv4(), Port.tcp(22), "SSH Access");

        // IAM Role for EC2 instance
        Role ec2Role = Role.Builder.create(this, "MinecraftEC2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromManagedPolicyArn(
                                this,
                                "SSMManagedPolicy",
                                "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
                        )
                ))
                .build();

        // SSH KeyPair
        IKeyPair keyPair = KeyPair.fromKeyPairName(this, "key-09e0010f25fc66508", "Minecraft");

        // EC2 Instance
        Instance ec2 = Instance.Builder.create(this, "MinecraftServer")
                .vpc(vpc)
                .instanceType(InstanceType.of(InstanceClass.M7I_FLEX, InstanceSize.LARGE))
                .machineImage(MachineImage.genericLinux(Map.of(
                        "us-east-1", "ami-0f9c27b471bdcd702"
                )))
                .vpcSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PUBLIC)  // Changed to PUBLIC for direct access
                        .build())
                .securityGroup(sg)
                .role(ec2Role)
                .keyPair(keyPair)
                .blockDevices(List.of(
                        BlockDevice.builder()
                                .deviceName("/dev/xvda")
                                .volume(BlockDeviceVolume.ebs(12, EbsDeviceOptions.builder()
                                        .volumeType(EbsDeviceVolumeType.GP3)
                                        .deleteOnTermination(true)
                                        .build()))
                                .build()
                ))
                .build();

        ec2.addUserData(userData);

        // Elastic IP for static public address
        CfnEIP eip = CfnEIP.Builder.create(this, "MinecraftEIP")
                .domain("vpc")
                .build();  // Remove instanceId from here

        CfnEIPAssociation.Builder.create(this, "MinecraftEIPAssociation")
                .allocationId(eip.getAttrAllocationId())
                .instanceId(ec2.getInstanceId())
                .build();

        // Lambda Execution Role
        Role lambdaRole = Role.Builder.create(this, "LambdaEC2Role")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromManagedPolicyArn(
                                this,
                                "LambdaBasicExecution",
                                "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
                        )
                ))
                .build();

        // Add EC2 permissions
        lambdaRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ec2:StartInstances", "ec2:StopInstances", "ec2:DescribeInstances"))
                .resources(List.of("*"))
                .build());

        // Start Lambda Function
        Function startLambda = Function.Builder.create(this, "StartEC2Lambda")
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.lambda_handler")
                .timeout(Duration.seconds(10))
                .code(Code.fromInline(
                        "import boto3\n" +
                                "import json\n" +
                                "def lambda_handler(event, context):\n" +
                                "    ec2 = boto3.client('ec2')\n" +
                                "    instance_id = '" + ec2.getInstanceId() + "'\n" +
                                "    try:\n" +
                                "        response = ec2.start_instances(InstanceIds=[instance_id])\n" +
                                "        return {\n" +
                                "            'statusCode': 200,\n" +
                                "            'headers': {'Content-Type': 'application/json'},\n" +
                                "            'body': json.dumps({'message': 'Server starting', 'instanceId': instance_id})\n" +
                                "        }\n" +
                                "    except Exception as e:\n" +
                                "        return {\n" +
                                "            'statusCode': 500,\n" +
                                "            'headers': {'Content-Type': 'application/json'},\n" +
                                "            'body': json.dumps({'error': str(e)})\n" +
                                "        }\n"
                ))
                .role(lambdaRole)
                .build();

        // Stop Lambda Function
        Function stopLambda = Function.Builder.create(this, "StopEC2Lambda")
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.lambda_handler")
                .timeout(Duration.seconds(10))
                .code(Code.fromInline(
                        "import boto3\n" +
                                "import json\n" +
                                "def lambda_handler(event, context):\n" +
                                "    ec2 = boto3.client('ec2')\n" +
                                "    instance_id = '" + ec2.getInstanceId() + "'\n" +
                                "    try:\n" +
                                "        response = ec2.stop_instances(InstanceIds=[instance_id])\n" +
                                "        return {\n" +
                                "            'statusCode': 200,\n" +
                                "            'headers': {'Content-Type': 'application/json'},\n" +
                                "            'body': json.dumps({'message': 'Server stopping', 'instanceId': instance_id})\n" +
                                "        }\n" +
                                "    except Exception as e:\n" +
                                "        return {\n" +
                                "            'statusCode': 500,\n" +
                                "            'headers': {'Content-Type': 'application/json'},\n" +
                                "            'body': json.dumps({'error': str(e)})\n" +
                                "        }\n"
                ))
                .role(lambdaRole)
                .build();

        // Status Lambda Function
        Function statusLambda = Function.Builder.create(this, "StatusEC2Lambda")
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.lambda_handler")
                .timeout(Duration.seconds(10))
                .code(Code.fromInline(
                        "import boto3\n" +
                                "import json\n" +
                                "def lambda_handler(event, context):\n" +
                                "    ec2 = boto3.client('ec2')\n" +
                                "    instance_id = '" + ec2.getInstanceId() + "'\n" +
                                "    try:\n" +
                                "        response = ec2.describe_instances(InstanceIds=[instance_id])\n" +
                                "        state = response['Reservations'][0]['Instances'][0]['State']['Name']\n" +
                                "        return {\n" +
                                "            'statusCode': 200,\n" +
                                "            'headers': {'Content-Type': 'application/json'},\n" +
                                "            'body': json.dumps({'instanceId': instance_id, 'state': state})\n" +
                                "        }\n" +
                                "    except Exception as e:\n" +
                                "        return {\n" +
                                "            'statusCode': 500,\n" +
                                "            'headers': {'Content-Type': 'application/json'},\n" +
                                "            'body': json.dumps({'error': str(e)})\n" +
                                "        }\n"
                ))
                .role(lambdaRole)
                .build();

        // API Gateway
        LambdaRestApi api = LambdaRestApi.Builder.create(this, "MinecraftApi")
                .handler(startLambda)
                .proxy(false)
                .restApiName("Minecraft Server Control API")
                .description("API to control Minecraft game server")
                .build();

        Resource startResource = api.getRoot().addResource("start-server");
        startResource.addMethod("POST", new LambdaIntegration(startLambda));

        Resource stopResource = api.getRoot().addResource("stop-server");
        stopResource.addMethod("POST", new LambdaIntegration(stopLambda));

        Resource statusResource = api.getRoot().addResource("status");
        statusResource.addMethod("GET", new LambdaIntegration(statusLambda));

        // CloudFormation Outputs
        CfnOutput.Builder.create(this, "InstanceId")
                .value(ec2.getInstanceId())
                .description("EC2 Instance ID")
                .build();

        CfnOutput.Builder.create(this, "ServerPublicIP")
                .value(eip.getRef())
                .description("Public IP to connect to Minecraft server (port 2456) and SSH (port 22)")
                .build();

        CfnOutput.Builder.create(this, "GameServerAddress")
                .value(eip.getRef() + ":2456")
                .description("Direct connect address for Minecraft")
                .build();

        CfnOutput.Builder.create(this, "ApiEndpoint")
                .value(api.getUrl())
                .description("API Gateway endpoint URL")
                .build();

        CfnOutput.Builder.create(this, "StartServerEndpoint")
                .value(api.getUrl() + "start-server")
                .description("Endpoint to start the server (POST)")
                .build();

        CfnOutput.Builder.create(this, "StopServerEndpoint")
                .value(api.getUrl() + "stop-server")
                .description("Endpoint to stop the server (POST)")
                .build();

        CfnOutput.Builder.create(this, "StatusEndpoint")
                .value(api.getUrl() + "status")
                .description("Endpoint to check server status (GET)")
                .build();

    }
}
