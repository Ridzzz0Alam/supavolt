using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Supavolt.Api.Migrations
{
    /// <inheritdoc />
    public partial class ProjectDbRole : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "db_role_password",
                table: "projects",
                type: "text",
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "db_role_password",
                table: "projects");
        }
    }
}
