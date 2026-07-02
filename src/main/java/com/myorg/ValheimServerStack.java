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

public class ValheimServerStack extends Stack {
    public ValheimServerStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public ValheimServerStack(final Construct scope, final String id, final StackProps props) {

        super(scope, id, props);
        String userData = """
        #!/bin/bash
        set -e
        
        export DEBIAN_FRONTEND=noninteractive
        
        # 1. Update system
        apt-get update
        apt-get upgrade -y -o Dpkg::Options::="--force-confold"
        
        # 2. Create steam user
        useradd -m -s /bin/bash steam
        
        # 3. Enable 32-bit and install ALL dependencies BEFORE steamcmd
        dpkg --add-architecture i386
        apt-get update
        apt-get install -y \\
            lib32gcc-s1 \\
            lib32stdc++6 \\
            libsdl2-2.0-0:i386 \\
            libatomic1 \\
            libpulse-dev \\
            libpulse0 \\
            curl \\
            wget
        
        # 4. Install SteamCMD
        mkdir -p /opt/steamcmd
        cd /opt/steamcmd
        wget https://steamcdn-a.akamaihd.net/client/installer/steamcmd_linux.tar.gz
        tar -xvzf steamcmd_linux.tar.gz
        rm steamcmd_linux.tar.gz
        chown -R steam:steam /opt/steamcmd
        
        # 5. Create valheim dir with correct ownership
        mkdir -p /opt/valheim-server
        mkdir -p /opt/valheim-server/saves
        chown -R steam:steam /opt/valheim-server
        
        # 6. Install Valheim server as steam user
        
        cat > /opt/steamcmd/install_valheim.sh << 'EOFSCRIPT'
        #!/bin/bash
        su - steam -c "/opt/steamcmd/steamcmd.sh \\
            +@sSteamCmdForcePlatformType linux \\
            +force_install_dir /opt/valheim-server \\
            +login anonymous \\
            +app_update 896660 -beta public validate \\
            +quit"
EOFSCRIPT
        chmod +x /opt/steamcmd/install_valheim.sh
        chown steam:steam /opt/steamcmd/install_valheim.sh
        su - steam -c "/opt/steamcmd/install_valheim.sh"

        # 7. Create launch script
        cat > /opt/valheim-server/launch_server.sh << 'LAUNCHEREOF'
        #!/bin/bash
        cd /opt/valheim-server
        ./valheim_server.x86_64 \\
            -nographics \\
            -batchmode \\
            -name "My AWS Server" \\
            -port 2456 \\
            -world "AmazonVikingForest" \\
            -password "123123" \\
            -crossplay \\
            -public 0 \\
            -savedir /opt/valheim-server/saves \\
            -preset hard
LAUNCHEREOF
    
        chmod +x /opt/valheim-server/launch_server.sh
        chown steam:steam /opt/valheim-server/launch_server.sh
    
        # 8. Create systemd service
        cat > /etc/systemd/system/valheim.service << 'SERVICEEOF'
        [Unit]
        Description=Valheim Dedicated Server
        After=network.target

        [Service]
        Type=simple
        User=steam
        WorkingDirectory=/opt/valheim-server
        Environment=LD_LIBRARY_PATH=/opt/valheim-server/linux64
        # Automatically update the server before starting
        ExecStartPre=/opt/steamcmd/steamcmd.sh +@sSteamCmdForcePlatformType linux +force_install_dir /opt/valheim-server +login anonymous +app_update 896660 -beta public validate +quit
        ExecStart=/opt/valheim-server/launch_server.sh
        Restart=on-failure
        RestartSec=10
        StandardOutput=journal
        StandardError=journal
   
        [Install]
        WantedBy=multi-user.target
SERVICEEOF
    
        # Enable and start the service
        systemctl daemon-reload
        systemctl enable valheim.service
        systemctl start valheim.service
    
        echo "Valheim server installation complete!"
    """;

        // Create VPC with NAT Gateway for private subnet egress
        Vpc vpc = Vpc.Builder.create(this, "ValheimVpc")
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
        SecurityGroup sg = SecurityGroup.Builder.create(this, "ValheimSG")
                .vpc(vpc)
                .description("Security group for Valheim game server")
                .allowAllOutbound(true)
                .build();

        // Game port
        sg.addIngressRule(Peer.anyIpv4(), Port.udpRange(2456, 2458), "Valheim Game Ports");

        // Optional: SSH access (comment out if not needed)
        sg.addIngressRule(Peer.anyIpv4(), Port.tcp(22), "SSH Access");

        // IAM Role for EC2 instance
        Role ec2Role = Role.Builder.create(this, "ValheimEC2Role")
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
        IKeyPair keyPair = KeyPair.fromKeyPairName(this, "key-09e0010f25fc66508", "ValheimKeyPair");

        // EC2 Instance
        Instance ec2 = Instance.Builder.create(this, "ValheimServer")
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
        CfnEIP eip = CfnEIP.Builder.create(this, "ValheimEIP")
                .domain("vpc")
                .build();  // Remove instanceId from here

        CfnEIPAssociation.Builder.create(this, "ValheimEIPAssociation")
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
        LambdaRestApi api = LambdaRestApi.Builder.create(this, "ValheimApi")
                .handler(startLambda)
                .proxy(false)
                .restApiName("Valheim Server Control API")
                .description("API to control Valheim game server")
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
                .description("Public IP to connect to Valheim server (port 2456) and SSH (port 22)")
                .build();

        CfnOutput.Builder.create(this, "GameServerAddress")
                .value(eip.getRef() + ":2456")
                .description("Direct connect address for Valheim")
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
