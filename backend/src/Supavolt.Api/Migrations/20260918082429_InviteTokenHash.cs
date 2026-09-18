using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Supavolt.Api.Migrations
{
    /// <inheritdoc />
    public partial class InviteTokenHash : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "token_hash",
                table: "invites",
                type: "text",
                nullable: false,
                defaultValue: "");

            // Invites created before this column had no usable token; they can never be accepted.
            migrationBuilder.Sql("DELETE FROM invites WHERE token_hash = ''");

            migrationBuilder.CreateIndex(
                name: "ix_invites_token_hash",
                table: "invites",
                column: "token_hash",
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropIndex(
                name: "ix_invites_token_hash",
                table: "invites");

            migrationBuilder.DropColumn(
                name: "token_hash",
                table: "invites");
        }
    }
}
