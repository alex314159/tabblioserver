# tabblioserver

Backend server for [www.tabblio.com](https://www.tabblio.com) - A privacy-focused data analysis platform.

## Why This Repository is Public

This repository is intentionally made public to **demonstrate tabblio's privacy-first architecture**. By examining this codebase, you can verify that:

- **All data analysis happens client-side** in your browser
- **Your data files never leave your device** - they are never uploaded to our servers
- **Only analysis templates and metadata are stored** on the server
- **No user data is processed or stored server-side**

This transparency is core to tabblio's commitment to user privacy and data security.

## What is tabblio?

[tabblio](https://www.tabblio.com) is a browser-based data analysis platform that performs all computations locally in your browser. Whether you're working with CSV files, Excel spreadsheets, or other data formats, your data remains on your device throughout the entire analysis process.

### Privacy Architecture

```
┌─────────────────────┐
│   Your Browser      │
│  ┌──────────────┐   │
│  │  Your Data   │   │──┐
│  │   (CSV,      │   │  │ Data NEVER
│  │    Excel)    │   │  │ leaves your
│  └──────────────┘   │  │ device
│         │           │  │
│         ▼           │  │
│  ┌──────────────┐   │  │
│  │  Analysis    │   │  │
│  │  Engine      │   │  │
│  │ (Client-side)│   │  │
│  └──────────────┘   │──┘
│         │           │
│         ▼           │
│  ┌──────────────┐   │
│  │  Templates   │───┼──────────▶  tabblioserver
│  │  (Metadata)  │   │            (This Repository)
│  └──────────────┘   │
└─────────────────────┘
```

## What This Server Does

tabblioserver is a Clojure REST API that handles:

- **Template Management**: Save and load analysis templates (configurations, formulas, visualizations)
- **User Authentication**: Integration with [Clerk](https://clerk.com) for secure user management
- **Metadata Storage**: User preferences, template ownership, and usage analytics
- **Session Management**: Secure authentication and authorization

## What This Server Does NOT Do

- ❌ Process your data files
- ❌ Store your data files
- ❌ Analyze your data
- ❌ Have access to your raw data

## Technology Stack

- **Language**: Clojure 1.12.1
- **HTTP Server**: http-kit 2.8.1
- **Routing**: Reitit 0.9.2
- **Database**: SQLite 3.51.0.0
- **Authentication**: Clerk Backend API 3.2.0
- **Payments**: Clerk Billing (handled via Clerk)

## Quick Start

### Prerequisites

- [Leiningen](https://leiningen.org/) (Clojure build tool)
- Java 11 or higher

### Installation

1. Clone this repository:
   ```bash
   git clone https://github.com/alex314159/tabblioserver.git
   cd tabblioserver
   ```

2. Install dependencies:
   ```bash
   lein deps
   ```

3. Set up environment variables:
   ```bash
   cp .env.edn.example .env.edn
   # Edit .env.edn with your configuration
   ```

4. Run the server:
   ```bash
   lein run
   ```

The server will start on port 8082 by default.

## Configuration

Create a `.env.edn` file with the following configuration:

```clojure
{:database-url "jdbc:sqlite:tabblio.db"
 :port "8082"
 :clerk-secret-key "sk_test_..."
 :clerk-webhook-secret "whsec_..."}
```

**Note**: Never commit `.env.edn` to version control. It contains sensitive API keys.

## API Endpoints

### Public Endpoints
- `GET /` - Health check
- `POST /api/save-template` - Save analysis template
- `GET /api/load-template?uuid={uuid}` - Load template by UUID
- `POST /api/clerk-webhook` - Clerk authentication webhooks

### Authenticated Endpoints
- `POST /api/link-template` - Link template to user account
- `POST /api/unlink-template` - Unlink template from user
- `GET /api/user-templates` - Get user's templates
- `GET /api/files/:file-id` - Serve static files
- `GET /api/serve-url?url={url}` - Proxy external URLs

## Database Schema

The server uses SQLite with the following core tables:

- **users**: Clerk user lifecycle tracking
- **templates**: Analysis template storage (EDN serialized)
- **user_templates**: Template ownership mapping

## Development

### Running Tests
```bash
lein test
```

### REPL Development
```bash
lein repl
```

### Building for Production
```bash
lein uberjar
```

## Contributing & Bug Reports

We welcome contributions and bug reports for both the tabblio frontend and backend!

### Reporting Issues

**Frontend Issues**: Even though the frontend code is not publicly available, this repository is a great place to report frontend bugs, feature requests, or usability issues for [www.tabblio.com](https://www.tabblio.com). Please [file an issue](https://github.com/alex314159/tabblioserver/issues) with the `frontend` label.

**Backend Issues**: For issues related to this server codebase, API endpoints, or integration issues, please [file an issue](https://github.com/alex314159/tabblioserver/issues) with the `backend` label.

### Types of Contributions Welcome

- Bug reports (frontend or backend)
- Feature requests
- Documentation improvements
- Code contributions (pull requests)
- Security vulnerability reports
- Privacy concerns or questions

## Project Status

This project is in **active development** and approaching production readiness.

## Security

If you discover a security vulnerability, please email security@tabblio.com instead of filing a public issue. We take security seriously and will respond promptly.

## Privacy Commitment

tabblio is built on the principle that **your data belongs to you**. This open-source backend serves as proof of our commitment to privacy:

- Source code is available for security audit
- No data processing logic exists in the backend
- All analysis happens in your browser
- Templates contain metadata only, never raw data

## License

Copyright © 2025 AG Research Ltd

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

## Links

- Website: [www.tabblio.com](https://www.tabblio.com)
- Issues: [GitHub Issues](https://github.com/alex314159/tabblioserver/issues)
- Email: contact@tabblio.com

---

**Built with Clojure** | **Privacy-First** | **Open Source**