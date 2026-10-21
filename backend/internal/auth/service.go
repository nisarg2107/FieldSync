// Package auth is a deliberately small email/password + JWT demo.
// PostgreSQL stores users and memberships; bcrypt and jwt handle cryptography.
package auth

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"net/mail"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"
)

type User struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Email string `json:"email"`
}
type Team struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Role       string `json:"role"`
	InviteCode string `json:"inviteCode"`
}
type Member struct {
	User User   `json:"user"`
	Role string `json:"role"`
}
type Session struct {
	User  User   `json:"user"`
	Token string `json:"token"`
}
type Service struct {
	Pool   *pgxpool.Pool
	secret []byte
}

func New(pool *pgxpool.Pool, secret string) *Service {
	return &Service{Pool: pool, secret: []byte(secret)}
}
func randomID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b[:])
}

func (s *Service) Register(ctx context.Context, name, email, password string) (Session, error) {
	email = strings.ToLower(strings.TrimSpace(email))
	name = strings.TrimSpace(name)
	address, err := mail.ParseAddress(email)
	if err != nil || address.Address != email || len(email) > 200 || len(name) < 1 || len(name) > 100 || len(password) < 8 || len(password) > 72 {
		return Session{}, fmt.Errorf("use a name, valid email, and password of 8–72 bytes")
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return Session{}, err
	}
	user := User{ID: randomID(), Name: name, Email: email}
	_, err = s.Pool.Exec(ctx, `INSERT INTO app_users(id,name,email,password_hash) VALUES($1,$2,$3,$4)`, user.ID, user.Name, user.Email, hash)
	if err != nil {
		return Session{}, fmt.Errorf("account could not be created; email may already be registered")
	}
	return s.session(user)
}

func (s *Service) Login(ctx context.Context, email, password string) (Session, error) {
	var user User
	var hash []byte
	err := s.Pool.QueryRow(ctx, `SELECT id,name,email,password_hash FROM app_users WHERE email=$1`, strings.ToLower(strings.TrimSpace(email))).Scan(&user.ID, &user.Name, &user.Email, &hash)
	if err != nil || bcrypt.CompareHashAndPassword(hash, []byte(password)) != nil {
		return Session{}, fmt.Errorf("invalid email or password")
	}
	return s.session(user)
}
func (s *Service) session(user User) (Session, error) {
	claims := jwt.RegisteredClaims{Subject: user.ID, Issuer: "fieldsync", ExpiresAt: jwt.NewNumericDate(time.Now().Add(7 * 24 * time.Hour))}
	token, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(s.secret)
	return Session{User: user, Token: token}, err
}
func (s *Service) Authenticate(header string) (string, error) {
	if !strings.HasPrefix(header, "Bearer ") {
		return "", errors.New("sign in to access this team")
	}
	claims := &jwt.RegisteredClaims{}
	token, err := jwt.ParseWithClaims(strings.TrimPrefix(header, "Bearer "), claims, func(_ *jwt.Token) (any, error) { return s.secret, nil }, jwt.WithValidMethods([]string{"HS256"}), jwt.WithIssuer("fieldsync"), jwt.WithExpirationRequired())
	if err != nil || !token.Valid || claims.Subject == "" {
		return "", errors.New("session expired or invalid; sign in again")
	}
	return claims.Subject, nil
}
func (s *Service) Role(ctx context.Context, team, user string) (string, error) {
	var role string
	err := s.Pool.QueryRow(ctx, `SELECT role FROM memberships WHERE team_id=$1 AND user_id=$2`, team, user).Scan(&role)
	return role, err
}
func (s *Service) CreateTeam(ctx context.Context, user, name string) (Team, error) {
	name = strings.TrimSpace(name)
	if len(name) < 1 || len(name) > 100 {
		return Team{}, errors.New("team name must contain 1–100 bytes")
	}
	team := Team{ID: randomID(), Name: name, Role: "owner", InviteCode: randomID()}
	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return Team{}, err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `INSERT INTO teams(id,name,invite_code) VALUES($1,$2,$3)`, team.ID, team.Name, team.InviteCode); err != nil {
		return Team{}, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO memberships VALUES($1,$2,'owner')`, team.ID, user); err != nil {
		return Team{}, err
	}
	return team, tx.Commit(ctx)
}
func (s *Service) JoinTeam(ctx context.Context, user, code string) (Team, error) {
	var team Team
	err := s.Pool.QueryRow(ctx, `SELECT id,name FROM teams WHERE invite_code=$1`, strings.TrimSpace(code)).Scan(&team.ID, &team.Name)
	if err != nil {
		return Team{}, errors.New("invite code not found")
	}
	// Joining does not grant write access. The owner explicitly promotes editors.
	if _, err = s.Pool.Exec(ctx, `INSERT INTO memberships VALUES($1,$2,'viewer') ON CONFLICT DO NOTHING`, team.ID, user); err != nil {
		return Team{}, err
	}
	team.Role, err = s.Role(ctx, team.ID, user)
	return team, err
}
func (s *Service) Teams(ctx context.Context, user string) ([]Team, error) {
	rows, err := s.Pool.Query(ctx, `SELECT t.id,t.name,m.role,CASE WHEN m.role='owner' THEN t.invite_code ELSE '' END FROM teams t JOIN memberships m ON m.team_id=t.id WHERE m.user_id=$1 ORDER BY t.name`, user)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []Team{}
	for rows.Next() {
		var team Team
		if err = rows.Scan(&team.ID, &team.Name, &team.Role, &team.InviteCode); err != nil {
			return nil, err
		}
		result = append(result, team)
	}
	return result, rows.Err()
}
func (s *Service) Members(ctx context.Context, team string) ([]Member, error) {
	rows, err := s.Pool.Query(ctx, `SELECT u.id,u.name,u.email,m.role FROM memberships m JOIN app_users u ON u.id=m.user_id WHERE m.team_id=$1 ORDER BY u.name`, team)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []Member{}
	for rows.Next() {
		var m Member
		if err = rows.Scan(&m.User.ID, &m.User.Name, &m.User.Email, &m.Role); err != nil {
			return nil, err
		}
		result = append(result, m)
	}
	return result, rows.Err()
}
func (s *Service) SetRole(ctx context.Context, team, user, role string) error {
	if role != "editor" && role != "viewer" {
		return errors.New("choose editor or viewer")
	}
	result, err := s.Pool.Exec(ctx, `UPDATE memberships SET role=$3 WHERE team_id=$1 AND user_id=$2 AND role<>'owner'`, team, user, role)
	if err == nil && result.RowsAffected() != 1 {
		return errors.New("member not found or owner role is protected")
	}
	return err
}
