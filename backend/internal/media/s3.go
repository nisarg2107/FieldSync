package media

import (
	"context"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"io"
	"os"
)

// S3 is optional. Local folders keep the default demo independent of AWS accounts.
type s3Storage struct {
	client *s3.Client
	bucket string
}

func New(ctx context.Context, root, bucket, endpoint string) (*Store, error) {
	store := &Store{Root: root}
	if bucket == "" {
		return store, nil
	}
	region := os.Getenv("AWS_REGION")
	if region == "" {
		region = "us-east-1"
	}
	settings, err := config.LoadDefaultConfig(ctx, config.WithRegion(region))
	if err != nil {
		return nil, err
	}
	client := s3.NewFromConfig(settings, func(options *s3.Options) {
		if endpoint != "" {
			options.BaseEndpoint = aws.String(endpoint)
			options.UsePathStyle = true
		}
	})
	store.remote = &s3Storage{client: client, bucket: bucket}
	return store, nil
}
func (s *s3Storage) put(ctx context.Context, key string, body io.Reader, contentType string) error {
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{Bucket: &s.bucket, Key: &key, Body: body, ContentType: &contentType})
	return err
}
func (s *s3Storage) open(ctx context.Context, key string) (io.ReadCloser, error) {
	result, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: &s.bucket, Key: &key})
	if err != nil {
		return nil, err
	}
	return result.Body, nil
}
