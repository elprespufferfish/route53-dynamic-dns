# Route53 Dynamic DNS Updater

This tool leverages icanhazip.com to detect your current external IP address,
and updates a provided AWS Route53 A resource record set with that address.

Typically, you'll want to add this to a cron job

    @daily java -jar dyndns.jar --hosted-zone $HOSTED_ZONE_ID --record-set $RECORD_SET

To find your Hosted Zone ID and Resource Record Set, you can use

    aws route53 list-hosted-zones
    aws route53 list-resource-record-sets --hosted-zone-id $HOSTED_ZONE_ID
